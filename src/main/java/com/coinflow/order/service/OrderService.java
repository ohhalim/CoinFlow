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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final DomainEventRecorder eventRecorder;
    private final TransactionTemplate transactionTemplate;

    private final Map<Long, ReentrantLock> marketLocks = new ConcurrentHashMap<>();

    public OrderService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            OrderSequenceRepository orderSequenceRepository,
            WalletRepository walletRepository,
            TradeRepository tradeRepository,
            WalletLedgerRepository walletLedgerRepository,
            MatchingEngine matchingEngine,
            DomainEventRecorder eventRecorder,
            PlatformTransactionManager transactionManager
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.orderSequenceRepository = orderSequenceRepository;
        this.walletRepository = walletRepository;
        this.tradeRepository = tradeRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.matchingEngine = matchingEngine;
        this.eventRecorder = eventRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CreateOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {

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
        marketLock.lock();
        try {
            return transactionTemplate.execute(status -> {

                // clientOrderId 중복 검증
                if (request.clientOrderId() != null &&
                        orderRepository.existsByUserIdAndClientOrderId(currentUserId, request.clientOrderId())) {
                    throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
                }

                // self-trade 사전 검증 (MAT-006)
                if (matchingEngine.hasSelfTrade(market.getSymbol(), side, price, currentUserId)) {
                    throw new ApiException(ErrorCode.SELF_TRADE_NOT_ALLOWED);
                }

                // sequence 발급
                OrderSequence seq = orderSequenceRepository.findByMarketIdWithLock(market.getId())
                        .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
                Long sequence = seq.nextSequence();

                // wallet lock
                Wallet wallet = walletRepository.findByUserIdAndAssetWithLock(currentUserId, lockedAsset)
                        .orElseThrow(() -> new ApiException(ErrorCode.INSUFFICIENT_BALANCE));
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
                orderRepository.save(order);
                eventRecorder.recordOrderAccepted(order);

                // ORDER_LOCK ledger
                walletLedgerRepository.save(WalletLedger.create(
                        wallet, LedgerType.ORDER_LOCK,
                        lockedAmount.negate(), lockedAmount,
                        order.getId(), null
                ));

                // 매칭 계획 수립 (큐 미변경), 정산
                List<MatchResult> plan = matchingEngine.planMatch(market, order);
                List<Trade> trades = settle(market, order, plan);

                // 커밋 성공 후 오더북 반영 — DB 롤백 시 큐는 그대로
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            matchingEngine.applyMatchPlan(market, order, plan);
                        } catch (Exception e) {
                            log.error("오더북 applyMatchPlan 실패: orderId={}, 서버 재시작 또는 DB 체결 내역으로 오더북 재구성 필요", order.getId(), e);
                        }
                    }
                });

                return CreateOrderResponse.of(order, trades);
            });
        } finally {
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

                Order lockedOrder = orderRepository.findByIdAndUserId(orderId, currentUserId)
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
                matchingEngine.cancelOrder(lockedOrder.getMarketSymbol(), lockedOrder);
                eventRecorder.recordOrderCanceled(lockedOrder, lockedOrder.getLockedAsset(), releaseAmount.toPlainString());

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
    public List<OrderSummaryResponse> getOrders(Long currentUserId, String market) {
        List<Order> orders = (market != null)
                ? orderRepository.findAllByUserIdAndMarketSymbolOrderByCreatedAtDesc(currentUserId, market)
                : orderRepository.findAllByUserIdOrderByCreatedAtDesc(currentUserId);
        return orders.stream().map(OrderSummaryResponse::from).toList();
    }

    private List<Trade> settle(Market market, Order taker, List<MatchResult> matchResults) {
        if (matchResults.isEmpty()) return List.of();

        List<Trade> trades = new ArrayList<>();

        for (MatchResult result : matchResults) {
            Order maker = orderRepository.findByIdWithLock(result.makerOrderId()).orElseThrow();
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

            Wallet buyerBaseWallet   = walletRepository.findByUserIdAndAssetWithLock(result.buyUserId(),  market.getBaseAsset()).orElseThrow();
            Wallet sellerQuoteWallet = walletRepository.findByUserIdAndAssetWithLock(result.sellUserId(), market.getQuoteAsset()).orElseThrow();
            Wallet sellerBaseWallet  = walletRepository.findByUserIdAndAssetWithLock(result.sellUserId(), market.getBaseAsset()).orElseThrow();
            Wallet buyerQuoteWallet  = walletRepository.findByUserIdAndAssetWithLock(result.buyUserId(),  market.getQuoteAsset()).orElseThrow();

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

            eventRecorder.recordSettlementCompleted(trade);
            trades.add(trade);
        }

        return trades;
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
}
