package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSequence;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderType;
import com.coinflow.order.domain.TimeInForce;
import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.CreateOrderResponse;
import com.coinflow.order.dto.OrderDetailResponse;
import com.coinflow.order.dto.OrderSummaryResponse;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.order.matching.MatchResult;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookRecoveryService;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.repository.OrderSequenceRepository;
import com.coinflow.trade.domain.Trade;
import com.coinflow.trade.repository.TradeRepository;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import com.coinflow.common.pagination.OffsetBasedPageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Slf4j
@Service
public class OrderService {

    private static final String ORDER_CREATE_STAGE_TIMER = "order.create.stage.duration";

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final OrderSequenceRepository orderSequenceRepository;
    private final WalletRepository walletRepository;
    private final TradeRepository tradeRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MatchingEngine matchingEngine;
    private final OrderBookRecoveryService orderBookRecoveryService;
    private final DomainEventRecorder eventRecorder;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    private final Map<Long, ReentrantLock> marketLocks = new ConcurrentHashMap<>();

    public OrderService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            OrderSequenceRepository orderSequenceRepository,
            WalletRepository walletRepository,
            TradeRepository tradeRepository,
            WalletLedgerRepository walletLedgerRepository,
            MatchingEngine matchingEngine,
            OrderBookRecoveryService orderBookRecoveryService,
            DomainEventRecorder eventRecorder,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.orderSequenceRepository = orderSequenceRepository;
        this.walletRepository = walletRepository;
        this.tradeRepository = tradeRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.matchingEngine = matchingEngine;
        this.orderBookRecoveryService = orderBookRecoveryService;
        this.eventRecorder = eventRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    public CreateOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {
        long createStartedAt = System.nanoTime();

        // 1. market 조회 및 검증
        Market market = marketRepository.findBySymbol(request.market())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        if (!market.isActive()) throw new ApiException(ErrorCode.MARKET_NOT_ACTIVE);
        if (market.isCancelOnly()) throw new ApiException(ErrorCode.MARKET_CANCEL_ONLY);

        // 2. 파싱 및 정책 검증
        OrderSide side = parseSide(request.side());
        OrderType type = parseType(request.type());
        TimeInForce tif = parseTif(request.timeInForce());
        BigDecimal price    = parseBigDecimal(request.price(),    ErrorCode.INVALID_PRICE);
        BigDecimal quantity = parseBigDecimal(request.quantity(), ErrorCode.INVALID_QUANTITY);
        if (price.compareTo(BigDecimal.ZERO) <= 0)    throw new ApiException(ErrorCode.INVALID_PRICE);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) throw new ApiException(ErrorCode.INVALID_QUANTITY);
        if (price.remainder(market.getTickSize()).compareTo(BigDecimal.ZERO) != 0)
            throw new ApiException(ErrorCode.INVALID_TICK_SIZE);
        if (quantity.remainder(market.getStepSize()).compareTo(BigDecimal.ZERO) != 0)
            throw new ApiException(ErrorCode.INVALID_STEP_SIZE);
        if (quantity.compareTo(market.getMinOrderQuantity()) < 0)
            throw new ApiException(ErrorCode.MIN_ORDER_QUANTITY_NOT_MET);
        if (price.multiply(quantity).compareTo(market.getMinOrderAmount()) < 0)
            throw new ApiException(ErrorCode.MIN_ORDER_AMOUNT_NOT_MET);

        String lockedAsset;
        BigDecimal lockedAmount;
        if (side == OrderSide.BUY) {
            lockedAsset = market.getQuoteAsset();
            lockedAmount = price.multiply(quantity).setScale(market.getAmountScale(), RoundingMode.CEILING);
        } else {
            lockedAsset = market.getBaseAsset();
            lockedAmount = quantity;
        }

        // 3. 시장별 lock 획득
        ReentrantLock marketLock = marketLocks.computeIfAbsent(market.getId(), k -> new ReentrantLock());
        long marketLockWaitStartedAt = System.nanoTime();
        marketLock.lock();
        long marketLockAcquiredAt = System.nanoTime();
        recordOrderCreateStage(market.getSymbol(), side, "market_lock_wait",
                marketLockAcquiredAt - marketLockWaitStartedAt);
        try {
            return recordOrderCreateStage(market.getSymbol(), side, "transaction_template", () ->
                    transactionTemplate.execute(status -> {
                long transactionCallbackStartedAt = System.nanoTime();
                try {

                // clientOrderId 중복 검증
                if (request.clientOrderId() != null) {
                    boolean duplicatedClientOrderId = recordOrderCreateStage(
                            market.getSymbol(), side, "client_order_id_check",
                            () -> orderRepository.existsByUserIdAndClientOrderId(currentUserId, request.clientOrderId())
                    );
                    if (duplicatedClientOrderId) {
                        throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
                    }
                }

                // self-trade 사전 검증 (MAT-006)
                boolean hasSelfTrade = recordOrderCreateStage(
                        market.getSymbol(), side, "self_trade_check",
                        () -> matchingEngine.hasSelfTrade(market.getSymbol(), side, price, currentUserId)
                );
                if (hasSelfTrade) {
                    throw new ApiException(ErrorCode.SELF_TRADE_NOT_ALLOWED);
                }

                // sequence 발급
                OrderSequence seq = recordOrderCreateStage(
                        market.getSymbol(), side, "sequence_lock",
                        () -> orderSequenceRepository.findByMarketIdWithLock(market.getId())
                                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND))
                );
                Long sequence = seq.nextSequence();

                // wallet lock
                Wallet wallet = recordOrderCreateStage(
                        market.getSymbol(), side, "taker_wallet_lock",
                        () -> walletRepository.findByUserIdAndAssetWithLock(currentUserId, lockedAsset)
                                .orElseThrow(() -> new ApiException(ErrorCode.INSUFFICIENT_BALANCE))
                );
                if (wallet.getAvailableBalance().compareTo(lockedAmount) < 0)
                    throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
                wallet.lock(lockedAmount);

                // order 저장
                Order order = Order.create(
                        currentUserId, market.getId(), market.getSymbol(),
                        side, type, tif,
                        price, quantity,
                        lockedAsset, lockedAmount,
                        sequence, request.clientOrderId()
                );
                recordOrderCreateStage(market.getSymbol(), side, "order_save",
                        () -> orderRepository.save(order));
                eventRecorder.recordOrderAccepted(order);

                // ORDER_LOCK ledger
                recordOrderCreateStage(market.getSymbol(), side, "order_lock_ledger_save", () ->
                        walletLedgerRepository.save(WalletLedger.create(
                        wallet, LedgerType.ORDER_LOCK,
                        lockedAmount.negate(), lockedAmount,
                        order.getId(), null
                )));

                // 매칭 계획 수립 (큐 미변경), 정산
                List<MatchResult> plan = recordOrderCreateStage(
                        market.getSymbol(), side, "matching_plan",
                        () -> matchingEngine.planMatch(market, order)
                );
                List<Order> autoCanceledMakers = new ArrayList<>();
                List<Trade> trades = recordOrderCreateStage(
                        market.getSymbol(), side, "settlement",
                        () -> settle(market, order, plan, autoCanceledMakers)
                );

                // 커밋 성공 후 오더북 반영 — DB 롤백 시 큐는 그대로
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        long orderBookApplyStartedAt = System.nanoTime();
                        try {
                            matchingEngine.applyMatchPlan(market, order, plan);
                            autoCanceledMakers.forEach(canceledMaker ->
                                    matchingEngine.cancelOrder(market.getSymbol(), canceledMaker));
                        } catch (Exception e) {
                            log.error("오더북 applyMatchPlan 실패: orderId={}, DB 체결 내역 기반 재빌드 시도", order.getId(), e);
                            orderBookRecoveryService.rebuildAfterApplyFailure(market.getId());
                        } finally {
                            recordOrderCreateStage(market.getSymbol(), side, "orderbook_after_commit",
                                    System.nanoTime() - orderBookApplyStartedAt);
                        }
                    }
                });

                return CreateOrderResponse.of(order, trades);
                } finally {
                    recordOrderCreateStage(market.getSymbol(), side, "transaction_callback",
                            System.nanoTime() - transactionCallbackStartedAt);
                }
            }));
        } finally {
            recordOrderCreateStage(market.getSymbol(), side, "market_lock_hold",
                    System.nanoTime() - marketLockAcquiredAt);
            recordOrderCreateStage(market.getSymbol(), side, "total",
                    System.nanoTime() - createStartedAt);
            marketLock.unlock();
        }
    }

    public CancelOrderResponse cancelOrder(Long currentUserId, Long orderId) {

        // lock 획득을 위해 먼저 marketId 조회
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

        ReentrantLock marketLock = marketLocks.computeIfAbsent(order.getMarketId(), k -> new ReentrantLock());
        marketLock.lock();
        try {
            return transactionTemplate.execute(status -> {

                Order lockedOrder = orderRepository.findByIdAndUserIdWithLock(orderId, currentUserId)
                        .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
                if (!lockedOrder.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

                BigDecimal releaseAmount = lockedOrder.releasableAmount();
                Wallet wallet = walletRepository.findByUserIdAndAssetWithLock(currentUserId, lockedOrder.getLockedAsset())
                        .orElseThrow(() -> new ApiException(ErrorCode.INSUFFICIENT_BALANCE));
                wallet.unlock(releaseAmount);

                walletLedgerRepository.save(WalletLedger.create(
                        wallet, LedgerType.ORDER_CANCEL_RELEASE,
                        releaseAmount, releaseAmount.negate(),
                        orderId, null
                ));

                lockedOrder.cancel();
                eventRecorder.recordOrderCanceled(lockedOrder, lockedOrder.getLockedAsset(), releaseAmount.toPlainString());

                String marketSymbol = lockedOrder.getMarketSymbol();
                Order canceledOrder = lockedOrder;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            matchingEngine.cancelOrder(marketSymbol, canceledOrder);
                        } catch (Exception e) {
                            log.error("오더북 cancelOrder 실패: orderId={}, DB 취소 완료 but 오더북에 잔존", canceledOrder.getId(), e);
                        }
                    }
                });

                return CancelOrderResponse.of(lockedOrder, lockedOrder.getLockedAsset(), releaseAmount.toPlainString());
            });
        } finally {
            marketLock.unlock();
        }
    }

    public ReentrantLock getMarketLock(Long marketId) {
        return marketLocks.computeIfAbsent(marketId, k -> new ReentrantLock());
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long currentUserId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        return OrderDetailResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrders(Long currentUserId, String market, int limit, int offset) {
        var pageable = new OffsetBasedPageRequest(offset, limit);
        List<Order> orders = (market != null)
                ? orderRepository.findAllByUserIdAndMarketSymbolOrderByCreatedAtDesc(currentUserId, market, pageable)
                : orderRepository.findAllByUserIdOrderByCreatedAtDesc(currentUserId, pageable);
        return orders.stream().map(OrderSummaryResponse::from).toList();
    }

    private List<Trade> settle(Market market, Order taker, List<MatchResult> matchResults, List<Order> autoCanceledMakers) {
        if (matchResults.isEmpty()) return List.of();

        List<Trade> trades = new ArrayList<>();

        for (MatchResult result : matchResults) {
            Order maker = recordOrderCreateStage(
                    market.getSymbol(), taker.getSide(), "maker_order_lock",
                    () -> orderRepository.findByIdWithLock(result.makerOrderId()).orElseThrow()
            );
            if (!maker.isCancelable()) {
                throw new ApiException(ErrorCode.ORDER_NOT_FOUND);
            }

            boolean takerIsBuy = taker.getSide() == OrderSide.BUY;
            Order buyOrder = takerIsBuy ? taker : maker;

            // Capture buy order's locked amount BEFORE fill so we can compute what was released
            BigDecimal oldBuyLocked = buyOrder.getLockedAmount();

            maker.fill(result.quantity(), result.quoteAmount(), market.getAmountScale());
            taker.fill(result.quantity(), result.quoteAmount(), market.getAmountScale());

            // buyerReleased = portion of locked consumed this fill (quoteAmount paid + any rounding refund)
            BigDecimal buyerReleased = oldBuyLocked.subtract(buyOrder.getLockedAmount());
            BigDecimal buyerRefund   = buyerReleased.subtract(result.quoteAmount());

            WalletKey buyerBaseKey = new WalletKey(result.buyUserId(), market.getBaseAsset());
            WalletKey sellerQuoteKey = new WalletKey(result.sellUserId(), market.getQuoteAsset());
            WalletKey sellerBaseKey = new WalletKey(result.sellUserId(), market.getBaseAsset());
            WalletKey buyerQuoteKey = new WalletKey(result.buyUserId(), market.getQuoteAsset());
            Map<WalletKey, Wallet> wallets = lockWalletsInOrder(
                    market.getSymbol(),
                    taker.getSide(),
                    buyerBaseKey,
                    sellerQuoteKey,
                    sellerBaseKey,
                    buyerQuoteKey
            );

            Wallet buyerBaseWallet   = wallets.get(buyerBaseKey);
            Wallet sellerQuoteWallet = wallets.get(sellerQuoteKey);
            Wallet sellerBaseWallet  = wallets.get(sellerBaseKey);
            Wallet buyerQuoteWallet  = wallets.get(buyerQuoteKey);

            buyerQuoteWallet.consumeLocked(buyerReleased);
            if (buyerRefund.compareTo(BigDecimal.ZERO) > 0) {
                buyerQuoteWallet.deposit(buyerRefund);
            }
            buyerBaseWallet.deposit(result.quantity());
            sellerBaseWallet.consumeLocked(result.quantity());
            sellerQuoteWallet.deposit(result.quoteAmount());

            Trade trade = Trade.create(
                    market.getId(), market.getSymbol(),
                    result.buyOrderId(), result.sellOrderId(),
                    result.makerOrderId(), result.takerOrderId(),
                    result.buyUserId(), result.sellUserId(),
                    result.price(), result.quantity(), result.quoteAmount()
            );
            tradeRepository.save(trade);

            Long buyOrderId  = result.buyOrderId();
            Long sellOrderId = result.sellOrderId();
            Long tradeId     = trade.getId();

            eventRecorder.recordOrderFillEvent(maker, tradeId);
            eventRecorder.recordOrderFillEvent(taker, tradeId);
            eventRecorder.recordTradeCreated(trade);

            walletLedgerRepository.save(WalletLedger.create(
                    buyerQuoteWallet, LedgerType.TRADE_BUY_QUOTE_SETTLE,
                    buyerRefund, buyerReleased.negate(),
                    buyOrderId, tradeId
            ));
            walletLedgerRepository.save(WalletLedger.create(
                    buyerBaseWallet, LedgerType.TRADE_BUY_BASE_CREDIT,
                    result.quantity(), BigDecimal.ZERO,
                    buyOrderId, tradeId
            ));
            walletLedgerRepository.save(WalletLedger.create(
                    sellerBaseWallet, LedgerType.TRADE_SELL_BASE_SETTLE,
                    BigDecimal.ZERO, result.quantity().negate(),
                    sellOrderId, tradeId
            ));
            walletLedgerRepository.save(WalletLedger.create(
                    sellerQuoteWallet, LedgerType.TRADE_SELL_QUOTE_CREDIT,
                    result.quoteAmount(), BigDecimal.ZERO,
                    sellOrderId, tradeId
            ));

            // Dust maker 자동 취소 (PRD 9절): 체결 후 남은 수량의 quote value가 0이면 잔여 lock 해제 후 CANCELED
            if (maker.getRemainingQuantity().signum() > 0) {
                BigDecimal dustCheck = maker.getPrice()
                        .multiply(maker.getRemainingQuantity())
                        .setScale(market.getAmountScale(), RoundingMode.DOWN);
                if (dustCheck.signum() == 0) {
                    Wallet makerLockedWallet = takerIsBuy ? sellerBaseWallet : buyerQuoteWallet;
                    BigDecimal dustRelease = maker.releasableAmount();
                    makerLockedWallet.unlock(dustRelease);
                    maker.cancel();
                    autoCanceledMakers.add(maker);
                    walletLedgerRepository.save(WalletLedger.create(
                            makerLockedWallet, LedgerType.ORDER_CANCEL_RELEASE,
                            dustRelease, dustRelease.negate(),
                            maker.getId(), tradeId
                    ));
                    eventRecorder.recordOrderCanceled(maker, maker.getLockedAsset(), dustRelease.toPlainString());
                }
            }

            eventRecorder.recordSettlementCompleted(trade);
            trades.add(trade);
        }

        return trades;
    }

    private Map<WalletKey, Wallet> lockWalletsInOrder(String marketSymbol, OrderSide side, WalletKey... keys) {
        List<WalletKey> sortedKeys = Stream.of(keys)
                .distinct()
                .sorted()
                .toList();

        Map<WalletKey, Wallet> wallets = new HashMap<>();
        for (WalletKey key : sortedKeys) {
            Wallet wallet = recordOrderCreateStage(
                    marketSymbol, side, "settlement_wallet_lock",
                    () -> walletRepository.findByUserIdAndAssetWithLock(key.userId(), key.asset())
                            .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND))
            );
            wallets.put(key, wallet);
        }
        return wallets;
    }

    private record WalletKey(Long userId, String asset) implements Comparable<WalletKey> {
        @Override
        public int compareTo(WalletKey other) {
            int userCompare = this.userId.compareTo(other.userId);
            if (userCompare != 0) return userCompare;
            return this.asset.compareTo(other.asset);
        }
    }

    private OrderSide parseSide(String value) {
        try { return OrderSide.valueOf(value); }
        catch (IllegalArgumentException e) { throw new ApiException(ErrorCode.INVALID_ORDER_SIDE); }
    }

    private OrderType parseType(String value) {
        try { return OrderType.valueOf(value); }
        catch (IllegalArgumentException e) { throw new ApiException(ErrorCode.INVALID_ORDER_TYPE); }
    }

    private TimeInForce parseTif(String value) {
        try { return TimeInForce.valueOf(value); }
        catch (IllegalArgumentException e) { throw new ApiException(ErrorCode.INVALID_REQUEST); }
    }

    private BigDecimal parseBigDecimal(String value, ErrorCode errorCode) {
        try { return new BigDecimal(value); }
        catch (NumberFormatException e) { throw new ApiException(errorCode); }
    }

    private <T> T recordOrderCreateStage(String marketSymbol, OrderSide side, String stage, Supplier<T> supplier) {
        long startedAt = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            recordOrderCreateStage(marketSymbol, side, stage, System.nanoTime() - startedAt);
        }
    }

    private void recordOrderCreateStage(String marketSymbol, OrderSide side, String stage, long elapsedNanos) {
        meterRegistry.timer(
                ORDER_CREATE_STAGE_TIMER,
                "market", marketSymbol,
                "side", side.name(),
                "stage", stage
        ).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
