package com.coinflow.order.service.processor;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.matching.MatchResult;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookRecoveryService;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.service.settlement.OrderSettlementService;
import com.coinflow.trade.domain.Trade;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.service.WalletOrderOperationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AcceptedOrderProcessor {

    private final OrderRepository orderRepository;
    private final WalletOrderOperationService walletOrderOperationService;
    private final MatchingEngine matchingEngine;
    private final OrderBookRecoveryService orderBookRecoveryService;
    private final DomainEventRecorder eventRecorder;
    private final OrderSettlementService orderSettlementService;
    private final TransactionTemplate transactionTemplate;

    public AcceptedOrderProcessor(
            OrderRepository orderRepository,
            WalletOrderOperationService walletOrderOperationService,
            MatchingEngine matchingEngine,
            OrderBookRecoveryService orderBookRecoveryService,
            DomainEventRecorder eventRecorder,
            OrderSettlementService orderSettlementService,
            PlatformTransactionManager transactionManager
    ) {
        this.orderRepository = orderRepository;
        this.walletOrderOperationService = walletOrderOperationService;
        this.matchingEngine = matchingEngine;
        this.orderBookRecoveryService = orderBookRecoveryService;
        this.eventRecorder = eventRecorder;
        this.orderSettlementService = orderSettlementService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void processAcceptedOrder(Market market, Long orderId) {
        try {
            processAcceptedOrderInternal(market, orderId);
        } catch (Throwable e) {
            log.warn("Accepted order processing failed. orderId={}, error={}", orderId, e.getMessage(), e);
            rejectAcceptedOrder(orderId, e.getClass().getSimpleName());
        }
    }

    private void processAcceptedOrderInternal(Market market, Long orderId) {
        transactionTemplate.execute(status -> {
            Order order = orderRepository.findByIdWithLock(orderId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
            if (order.getStatus() != OrderStatus.ACCEPTED) {
                return null;
            }

            order.open();
            List<MatchResult> plan = matchingEngine.planMatchRejectingSelfTrade(market, order);
            List<Order> autoCanceledMakers = new ArrayList<>();
            List<Trade> trades = orderSettlementService.settle(market, order, plan, autoCanceledMakers, null, false);

            registerOrderBookSynchronization(market, order, plan, autoCanceledMakers);
            return trades;
        });
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

    private void rejectAcceptedOrder(Long orderId, String reason) {
        transactionTemplate.execute(status -> {
            Order order = orderRepository.findByIdWithLock(orderId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
            if (order.getStatus() != OrderStatus.ACCEPTED) {
                return null;
            }
            BigDecimal releaseAmount = order.releasableAmount();
            String releasedAsset = order.getLockedAsset();
            walletOrderOperationService.releaseOrderLock(
                    order.getUserId(),
                    releasedAsset,
                    releaseAmount,
                    orderId,
                    LedgerType.ORDER_REJECT_RELEASE
            );
            order.reject();
            eventRecorder.recordOrderRejected(order, releasedAsset, releaseAmount.toPlainString(), reason);
            return null;
        });
    }
}
