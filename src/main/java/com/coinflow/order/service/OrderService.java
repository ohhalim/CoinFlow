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
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import com.coinflow.common.pagination.OffsetBasedPageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class OrderService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final OrderSequenceRepository orderSequenceRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MatchingEngine matchingEngine;
    private final OrderBookRecoveryService orderBookRecoveryService;
    private final DomainEventRecorder eventRecorder;
    private final MarketOrderLockManager marketOrderLockManager;
    private final OrderCreateValidator orderCreateValidator;
    private final OrderAssetLockService orderAssetLockService;
    private final OrderSettlementService orderSettlementService;
    private final OrderCreateStageRecorder stageRecorder;
    private final TransactionTemplate transactionTemplate;

    public OrderService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            OrderSequenceRepository orderSequenceRepository,
            WalletRepository walletRepository,
            WalletLedgerRepository walletLedgerRepository,
            MatchingEngine matchingEngine,
            OrderBookRecoveryService orderBookRecoveryService,
            DomainEventRecorder eventRecorder,
            MarketOrderLockManager marketOrderLockManager,
            OrderCreateValidator orderCreateValidator,
            OrderAssetLockService orderAssetLockService,
            OrderSettlementService orderSettlementService,
            OrderCreateStageRecorder stageRecorder,
            PlatformTransactionManager transactionManager
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.orderSequenceRepository = orderSequenceRepository;
        this.walletRepository = walletRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.matchingEngine = matchingEngine;
        this.orderBookRecoveryService = orderBookRecoveryService;
        this.eventRecorder = eventRecorder;
        this.marketOrderLockManager = marketOrderLockManager;
        this.orderCreateValidator = orderCreateValidator;
        this.orderAssetLockService = orderAssetLockService;
        this.orderSettlementService = orderSettlementService;
        this.stageRecorder = stageRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CreateOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {
        long createStartedAt = System.nanoTime();

        Market market = marketRepository.findBySymbol(request.market())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        CreateOrderCommand command = orderCreateValidator.validate(market, request);
        OrderSide side = command.side();

        if (request.clientOrderId() != null) {
            boolean duplicatedClientOrderId = stageRecorder.record(
                    market.getSymbol(), side, "client_order_id_check",
                    () -> orderRepository.existsByUserIdAndClientOrderId(currentUserId, request.clientOrderId())
            );
            if (duplicatedClientOrderId) {
                throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
            }
        }

        MarketLockScope marketLockScope = acquireMarketLock(market, side);
        AtomicBoolean releaseRegistered = new AtomicBoolean(false);

        long transactionTemplateStartedAt = System.nanoTime();
        TransactionLifecycleMetrics transactionMetrics = new TransactionLifecycleMetrics(
                market.getSymbol(), side, transactionTemplateStartedAt);
        try {
            try {
                return transactionTemplate.execute(status -> {
                    transactionMetrics.recordCallbackStarted();
                    long transactionCallbackStartedAt = System.nanoTime();
                    try {
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
                        stageRecorder.record(market.getSymbol(), side, "order_accepted_event_save", () -> {
                            eventRecorder.recordOrderAccepted(order);
                            return null;
                        });

                        orderAssetLockService.recordOrderLockLedger(wallet, order, command);

                        // 매칭 계획 수립 (큐 미변경), 정산
                        List<MatchResult> plan = stageRecorder.record(
                                market.getSymbol(), side, "matching_plan",
                                () -> matchingEngine.planMatchRejectingSelfTrade(market, order)
                        );
                        List<Order> autoCanceledMakers = new ArrayList<>();
                        List<Trade> trades = stageRecorder.record(
                                market.getSymbol(), side, "settlement",
                                () -> orderSettlementService.settle(market, order, plan, autoCanceledMakers)
                        );

                        // 커밋 성공 후 오더북 반영 — DB 롤백 시 큐는 그대로
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                transactionMetrics.recordBeforeCommit();
                            }

                            @Override
                            public void beforeCompletion() {
                                transactionMetrics.recordBeforeCompletion();
                            }

                            @Override
                            public void afterCommit() {
                                transactionMetrics.recordAfterCommitStarted();
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
                                    marketLockScope.release();
                                    transactionMetrics.recordAfterCommitFinished();
                                }
                            }

                            @Override
                            public void afterCompletion(int status) {
                                transactionMetrics.recordAfterCompletionStarted();
                                marketLockScope.release();
                                transactionMetrics.recordAfterCompletionFinished();
                            }
                        });
                        releaseRegistered.set(true);

                        return CreateOrderResponse.of(order, trades);
                    } finally {
                        transactionMetrics.recordCallbackFinished();
                        stageRecorder.record(market.getSymbol(), side, "transaction_callback",
                                System.nanoTime() - transactionCallbackStartedAt);
                    }
                });
            } catch (DataIntegrityViolationException e) {
                if (request.clientOrderId() != null && isDuplicateClientOrderId(e)) {
                    throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
                }
                throw e;
            }
        } finally {
            if (!releaseRegistered.get()) {
                marketLockScope.release();
            }
            long transactionTemplateFinishedAt = System.nanoTime();
            stageRecorder.record(market.getSymbol(), side, "transaction_template",
                    transactionTemplateFinishedAt - transactionTemplateStartedAt);
            transactionMetrics.recordTemplateReturned(transactionTemplateFinishedAt);
            stageRecorder.record(market.getSymbol(), side, "total",
                    System.nanoTime() - createStartedAt);
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

    private MarketLockScope acquireMarketLock(Market market, OrderSide side) {
        ReentrantLock marketLock = marketOrderLockManager.getLock(market.getId());
        long marketLockWaitStartedAt = System.nanoTime();
        marketLock.lock();
        long marketLockAcquiredAt = System.nanoTime();
        stageRecorder.record(market.getSymbol(), side, "market_lock_wait",
                marketLockAcquiredAt - marketLockWaitStartedAt);
        return new MarketLockScope(market.getSymbol(), side, marketLock, marketLockAcquiredAt);
    }

    private boolean isDuplicateClientOrderId(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && (
                message.contains("uq_orders_user_client_order")
                        || message.contains("uk_orders_user_client_order_id")
        );
    }

    private class MarketLockScope {
        private final String marketSymbol;
        private final OrderSide side;
        private final ReentrantLock lock;
        private final long acquiredAt;
        private boolean released;

        private MarketLockScope(String marketSymbol, OrderSide side, ReentrantLock lock, long acquiredAt) {
            this.marketSymbol = marketSymbol;
            this.side = side;
            this.lock = lock;
            this.acquiredAt = acquiredAt;
        }

        private void release() {
            if (released) {
                return;
            }
            released = true;
            stageRecorder.record(marketSymbol, side, "market_lock_hold", System.nanoTime() - acquiredAt);
            lock.unlock();
        }
    }

    private class TransactionLifecycleMetrics {
        private final String marketSymbol;
        private final OrderSide side;
        private final long templateStartedAt;

        private long callbackStartedAt = -1L;
        private long callbackFinishedAt = -1L;
        private long beforeCommitAt = -1L;
        private long beforeCompletionAt = -1L;
        private long afterCommitStartedAt = -1L;
        private long afterCommitFinishedAt = -1L;
        private long afterCompletionStartedAt = -1L;
        private long afterCompletionFinishedAt = -1L;

        private TransactionLifecycleMetrics(String marketSymbol, OrderSide side, long templateStartedAt) {
            this.marketSymbol = marketSymbol;
            this.side = side;
            this.templateStartedAt = templateStartedAt;
        }

        private void recordCallbackStarted() {
            callbackStartedAt = System.nanoTime();
            stageRecorder.record(marketSymbol, side, "transaction_begin",
                    callbackStartedAt - templateStartedAt);
        }

        private void recordCallbackFinished() {
            callbackFinishedAt = System.nanoTime();
        }

        private void recordBeforeCommit() {
            beforeCommitAt = System.nanoTime();
            if (callbackFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_before_commit_wait",
                        beforeCommitAt - callbackFinishedAt);
            }
        }

        private void recordBeforeCompletion() {
            beforeCompletionAt = System.nanoTime();
            if (beforeCommitAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_before_completion",
                        beforeCompletionAt - beforeCommitAt);
            }
        }

        private void recordAfterCommitStarted() {
            afterCommitStartedAt = System.nanoTime();
            if (beforeCompletionAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_commit",
                        afterCommitStartedAt - beforeCompletionAt);
            }
        }

        private void recordAfterCommitFinished() {
            afterCommitFinishedAt = System.nanoTime();
            if (afterCommitStartedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_commit_callbacks",
                        afterCommitFinishedAt - afterCommitStartedAt);
            }
        }

        private void recordAfterCompletionStarted() {
            afterCompletionStartedAt = System.nanoTime();
            if (afterCommitFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_commit_to_completion",
                        afterCompletionStartedAt - afterCommitFinishedAt);
            }
        }

        private void recordAfterCompletionFinished() {
            afterCompletionFinishedAt = System.nanoTime();
            if (afterCompletionStartedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_completion_callbacks",
                        afterCompletionFinishedAt - afterCompletionStartedAt);
            }
        }

        private void recordTemplateReturned(long templateFinishedAt) {
            if (callbackFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_callback",
                        templateFinishedAt - callbackFinishedAt);
            }
            if (afterCompletionFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_completion_to_return",
                        templateFinishedAt - afterCompletionFinishedAt);
            }
        }
    }
}
