package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.CreateOrderResponse;
import com.coinflow.order.matching.MatchResult;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookRecoveryService;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.trade.domain.Trade;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SyncOrderProcessor {

    private final OrderRepository orderRepository;
    private final MatchingEngine matchingEngine;
    private final OrderBookRecoveryService orderBookRecoveryService;
    private final DomainEventRecorder eventRecorder;
    private final OrderAssetLockService orderAssetLockService;
    private final OrderSettlementService orderSettlementService;
    private final MarketSequenceAllocator marketSequenceAllocator;
    private final OrderCreateStageRecorder stageRecorder;
    private final MarketOrderLockService marketOrderLockService;
    private final OrderTransactionLifecycleRecorder transactionLifecycleRecorder;
    private final ClientOrderIdService clientOrderIdService;
    private final TransactionTemplate transactionTemplate;

    public SyncOrderProcessor(
            OrderRepository orderRepository,
            MatchingEngine matchingEngine,
            OrderBookRecoveryService orderBookRecoveryService,
            DomainEventRecorder eventRecorder,
            OrderAssetLockService orderAssetLockService,
            OrderSettlementService orderSettlementService,
            MarketSequenceAllocator marketSequenceAllocator,
            OrderCreateStageRecorder stageRecorder,
            MarketOrderLockService marketOrderLockService,
            OrderTransactionLifecycleRecorder transactionLifecycleRecorder,
            ClientOrderIdService clientOrderIdService,
            PlatformTransactionManager transactionManager
    ) {
        this.orderRepository = orderRepository;
        this.matchingEngine = matchingEngine;
        this.orderBookRecoveryService = orderBookRecoveryService;
        this.eventRecorder = eventRecorder;
        this.orderAssetLockService = orderAssetLockService;
        this.orderSettlementService = orderSettlementService;
        this.marketSequenceAllocator = marketSequenceAllocator;
        this.stageRecorder = stageRecorder;
        this.marketOrderLockService = marketOrderLockService;
        this.transactionLifecycleRecorder = transactionLifecycleRecorder;
        this.clientOrderIdService = clientOrderIdService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CreateOrderResponse process(
            Long currentUserId,
            CreateOrderRequest request,
            Market market,
            CreateOrderCommand command,
            long createStartedAt
    ) {
        OrderSide side = command.side();
        MarketOrderLockScope marketLockScope = marketOrderLockService.acquire(market, side);
        AtomicBoolean releaseRegistered = new AtomicBoolean(false);

        long transactionTemplateStartedAt = System.nanoTime();
        OrderTransactionLifecycleRecorder.Metrics transactionMetrics = transactionLifecycleRecorder.start(
                market.getSymbol(), side, transactionTemplateStartedAt);
        try {
            try {
                return transactionTemplate.execute(status -> executeInTransaction(
                        currentUserId,
                        request,
                        market,
                        command,
                        side,
                        marketLockScope,
                        releaseRegistered,
                        transactionMetrics
                ));
            } catch (DataIntegrityViolationException e) {
                if (request.clientOrderId() != null && clientOrderIdService.isDuplicateConstraintViolation(e)) {
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

    private CreateOrderResponse executeInTransaction(
            Long currentUserId,
            CreateOrderRequest request,
            Market market,
            CreateOrderCommand command,
            OrderSide side,
            MarketOrderLockScope marketLockScope,
            AtomicBoolean releaseRegistered,
            OrderTransactionLifecycleRecorder.Metrics transactionMetrics
    ) {
        transactionMetrics.recordCallbackStarted();
        long transactionCallbackStartedAt = System.nanoTime();
        try {
            Long sequence = stageRecorder.record(
                    market.getSymbol(), side, "sequence_allocate",
                    () -> marketSequenceAllocator.nextSequence(market.getId())
            );
            Wallet wallet = orderAssetLockService.lockTakerWallet(currentUserId, command);
            Order order = createAndSaveOrder(currentUserId, request, market, command, sequence);
            WalletLedger orderLockLedger = orderAssetLockService.createOrderLockLedger(wallet, order, command);

            List<MatchResult> plan = stageRecorder.record(
                    market.getSymbol(), side, "matching_plan",
                    () -> matchingEngine.planMatchRejectingSelfTrade(market, order)
            );
            List<Order> autoCanceledMakers = new ArrayList<>();
            List<Trade> trades = stageRecorder.record(
                    market.getSymbol(), side, "settlement",
                    () -> orderSettlementService.settle(market, order, plan, autoCanceledMakers, orderLockLedger)
            );
            if (trades.isEmpty()) {
                recordAcceptedOrderWithoutTrade(market, side, order, orderLockLedger, command);
            }

            registerOrderBookSynchronization(
                    market,
                    side,
                    order,
                    plan,
                    autoCanceledMakers,
                    marketLockScope,
                    transactionMetrics
            );
            releaseRegistered.set(true);

            return CreateOrderResponse.of(order, trades);
        } finally {
            transactionMetrics.recordCallbackFinished();
            stageRecorder.record(market.getSymbol(), side, "transaction_callback",
                    System.nanoTime() - transactionCallbackStartedAt);
        }
    }

    private Order createAndSaveOrder(
            Long currentUserId,
            CreateOrderRequest request,
            Market market,
            CreateOrderCommand command,
            Long sequence
    ) {
        Order order = Order.create(
                currentUserId, market.getId(), market.getSymbol(),
                command.side(), command.type(), command.timeInForce(),
                command.price(), command.quantity(),
                command.lockedAsset(), command.lockedAmount(),
                sequence, request.clientOrderId()
        );
        stageRecorder.record(market.getSymbol(), command.side(), "order_save",
                () -> orderRepository.save(order));
        return order;
    }

    private void recordAcceptedOrderWithoutTrade(
            Market market,
            OrderSide side,
            Order order,
            WalletLedger orderLockLedger,
            CreateOrderCommand command
    ) {
        stageRecorder.record(market.getSymbol(), side, "order_accepted_event_save", () -> {
            eventRecorder.recordOrderAccepted(order);
            return null;
        });
        orderAssetLockService.saveOrderLockLedger(orderLockLedger, command);
    }

    private void registerOrderBookSynchronization(
            Market market,
            OrderSide side,
            Order order,
            List<MatchResult> plan,
            List<Order> autoCanceledMakers,
            MarketOrderLockScope marketLockScope,
            OrderTransactionLifecycleRecorder.Metrics transactionMetrics
    ) {
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
    }
}
