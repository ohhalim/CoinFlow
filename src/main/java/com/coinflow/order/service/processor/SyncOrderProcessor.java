package com.coinflow.order.service.processor;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.Order;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.CreateOrderResponse;
import com.coinflow.order.matching.MatchResult;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookRecoveryService;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.service.command.CreateOrderCommand;
import com.coinflow.order.service.lock.OrderAssetLockService;
import com.coinflow.order.service.settlement.OrderSettlementService;
import com.coinflow.order.service.support.ClientOrderIdService;
import com.coinflow.order.service.support.MarketSequenceAllocator;
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
        this.clientOrderIdService = clientOrderIdService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CreateOrderResponse process(
            Long currentUserId,
            CreateOrderRequest request,
            Market market,
            CreateOrderCommand command
    ) {
        try {
            return transactionTemplate.execute(status ->
                    executeInTransaction(currentUserId, request, market, command));
        } catch (DataIntegrityViolationException e) {
            if (request.clientOrderId() != null && clientOrderIdService.isDuplicateConstraintViolation(e)) {
                throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
            }
            throw e;
        }
    }

    private CreateOrderResponse executeInTransaction(
            Long currentUserId,
            CreateOrderRequest request,
            Market market,
            CreateOrderCommand command
    ) {
        Long sequence = marketSequenceAllocator.nextSequence(market.getId());
        Wallet wallet = orderAssetLockService.lockTakerWallet(currentUserId, command);
        Order order = createAndSaveOrder(currentUserId, request, market, command, sequence);
        WalletLedger orderLockLedger = orderAssetLockService.createOrderLockLedger(wallet, order, command);

        List<MatchResult> plan = matchingEngine.planMatchRejectingSelfTrade(market, order);
        List<Order> autoCanceledMakers = new ArrayList<>();
        List<Trade> trades = orderSettlementService.settle(market, order, plan, autoCanceledMakers, orderLockLedger);
        if (trades.isEmpty()) {
            recordAcceptedOrderWithoutTrade(order, orderLockLedger, command);
        }

        registerOrderBookSynchronization(market, order, plan, autoCanceledMakers);
        return CreateOrderResponse.of(order, trades);
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
        orderRepository.save(order);
        return order;
    }

    private void recordAcceptedOrderWithoutTrade(
            Order order,
            WalletLedger orderLockLedger,
            CreateOrderCommand command
    ) {
        eventRecorder.recordOrderAccepted(order);
        orderAssetLockService.saveOrderLockLedger(orderLockLedger, command);
    }

    private void registerOrderBookSynchronization(
            Market market,
            Order order,
            List<MatchResult> plan,
            List<Order> autoCanceledMakers
    ) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    matchingEngine.applyMatchPlan(market, order, plan);
                    autoCanceledMakers.forEach(canceledMaker ->
                            matchingEngine.cancelOrder(market.getSymbol(), canceledMaker));
                } catch (Exception e) {
                    log.error("오더북 applyMatchPlan 실패: orderId={}, DB 체결 내역 기반 재빌드 시도", order.getId(), e);
                    orderBookRecoveryService.rebuildAfterApplyFailure(market.getId());
                }
            }
        });
    }
}
