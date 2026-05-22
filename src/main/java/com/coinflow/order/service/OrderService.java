package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSequence;
import com.coinflow.order.domain.OrderSide;
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
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class OrderService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final OrderSequenceRepository orderSequenceRepository;
    private final WalletRepository walletRepository;
    private final TradeRepository tradeRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MatchingEngine matchingEngine;
    private final OrderBookRecoveryService orderBookRecoveryService;
    private final DomainEventRecorder eventRecorder;
    private final MarketOrderLockManager marketOrderLockManager;
    private final OrderCreateValidator orderCreateValidator;
    private final OrderAssetLockService orderAssetLockService;
    private final OrderCreateStageRecorder stageRecorder;
    private final TransactionTemplate transactionTemplate;

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
            MarketOrderLockManager marketOrderLockManager,
            OrderCreateValidator orderCreateValidator,
            OrderAssetLockService orderAssetLockService,
            OrderCreateStageRecorder stageRecorder,
            PlatformTransactionManager transactionManager
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
        this.marketOrderLockManager = marketOrderLockManager;
        this.orderCreateValidator = orderCreateValidator;
        this.orderAssetLockService = orderAssetLockService;
        this.stageRecorder = stageRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CreateOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {
        long createStartedAt = System.nanoTime();

        Market market = marketRepository.findBySymbol(request.market())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        CreateOrderCommand command = orderCreateValidator.validate(market, request);
        OrderSide side = command.side();

        // 3. 시장별 lock 획득
        ReentrantLock marketLock = marketOrderLockManager.getLock(market.getId());
        long marketLockWaitStartedAt = System.nanoTime();
        marketLock.lock();
        long marketLockAcquiredAt = System.nanoTime();
        stageRecorder.record(market.getSymbol(), side, "market_lock_wait",
                marketLockAcquiredAt - marketLockWaitStartedAt);
        try {
            return stageRecorder.record(market.getSymbol(), side, "transaction_template", () ->
                    transactionTemplate.execute(status -> {
                long transactionCallbackStartedAt = System.nanoTime();
                try {

                // clientOrderId 중복 검증
                if (request.clientOrderId() != null) {
                    boolean duplicatedClientOrderId = stageRecorder.record(
                            market.getSymbol(), side, "client_order_id_check",
                            () -> orderRepository.existsByUserIdAndClientOrderId(currentUserId, request.clientOrderId())
                    );
                    if (duplicatedClientOrderId) {
                        throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
                    }
                }

                // self-trade 사전 검증 (MAT-006)
                boolean hasSelfTrade = stageRecorder.record(
                        market.getSymbol(), side, "self_trade_check",
                        () -> matchingEngine.hasSelfTrade(market.getSymbol(), side, command.price(), currentUserId)
                );
                if (hasSelfTrade) {
                    throw new ApiException(ErrorCode.SELF_TRADE_NOT_ALLOWED);
                }

                // sequence 발급
                OrderSequence seq = stageRecorder.record(
                        market.getSymbol(), side, "sequence_lock",
                        () -> orderSequenceRepository.findByMarketIdWithLock(market.getId())
                                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND))
                );
                Long sequence = seq.nextSequence();

                Wallet wallet = orderAssetLockService.lockTakerWallet(currentUserId, command);

                // order 저장
                Order order = Order.create(
                        currentUserId, market.getId(), market.getSymbol(),
                        side, command.type(), command.timeInForce(),
                        command.price(), command.quantity(),
                        command.lockedAsset(), command.lockedAmount(),
                        sequence, request.clientOrderId()
                );
                stageRecorder.record(market.getSymbol(), side, "order_save",
                        () -> orderRepository.save(order));
                eventRecorder.recordOrderAccepted(order);

                orderAssetLockService.recordOrderLockLedger(wallet, order, command);

                // 매칭 계획 수립 (큐 미변경), 정산
                List<MatchResult> plan = stageRecorder.record(
                        market.getSymbol(), side, "matching_plan",
                        () -> matchingEngine.planMatch(market, order)
                );
                List<Order> autoCanceledMakers = new ArrayList<>();
                List<Trade> trades = stageRecorder.record(
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
                            stageRecorder.record(market.getSymbol(), side, "orderbook_after_commit",
                                    System.nanoTime() - orderBookApplyStartedAt);
                        }
                    }
                });

                return CreateOrderResponse.of(order, trades);
                } finally {
                    stageRecorder.record(market.getSymbol(), side, "transaction_callback",
                            System.nanoTime() - transactionCallbackStartedAt);
                }
            }));
        } finally {
            stageRecorder.record(market.getSymbol(), side, "market_lock_hold",
                    System.nanoTime() - marketLockAcquiredAt);
            stageRecorder.record(market.getSymbol(), side, "total",
                    System.nanoTime() - createStartedAt);
            marketLock.unlock();
        }
    }

    public CancelOrderResponse cancelOrder(Long currentUserId, Long orderId) {

        // lock 획득을 위해 먼저 marketId 조회
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

        ReentrantLock marketLock = marketOrderLockManager.getLock(order.getMarketId());
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
        return marketOrderLockManager.getLock(marketId);
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
            Order maker = stageRecorder.record(
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
            Wallet wallet = stageRecorder.record(
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

}
