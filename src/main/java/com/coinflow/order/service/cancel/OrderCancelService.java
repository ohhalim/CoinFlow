package com.coinflow.order.service.cancel;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.order.domain.Order;
import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.service.lock.MarketOrderLockScope;
import com.coinflow.order.service.lock.MarketOrderLockService;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.service.WalletOrderOperationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

@Slf4j
@Service
public class OrderCancelService {

    private final OrderRepository orderRepository;
    private final WalletOrderOperationService walletOrderOperationService;
    private final MatchingEngine matchingEngine;
    private final DomainEventRecorder eventRecorder;
    private final MarketOrderLockService marketOrderLockService;
    private final TransactionTemplate transactionTemplate;

    public OrderCancelService(
            OrderRepository orderRepository,
            WalletOrderOperationService walletOrderOperationService,
            MatchingEngine matchingEngine,
            DomainEventRecorder eventRecorder,
            MarketOrderLockService marketOrderLockService,
            PlatformTransactionManager transactionManager
    ) {
        this.orderRepository = orderRepository;
        this.walletOrderOperationService = walletOrderOperationService;
        this.matchingEngine = matchingEngine;
        this.eventRecorder = eventRecorder;
        this.marketOrderLockService = marketOrderLockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CancelOrderResponse cancelOrder(Long currentUserId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

        MarketOrderLockScope marketLockScope = marketOrderLockService.acquire(
                order.getMarketId(),
                order.getMarketSymbol(),
                order.getSide()
        );
        try {
            return transactionTemplate.execute(status -> cancelInTransaction(currentUserId, orderId));
        } finally {
            marketLockScope.release();
        }
    }

    private CancelOrderResponse cancelInTransaction(Long currentUserId, Long orderId) {
        Order lockedOrder = orderRepository.findByIdAndUserIdWithLock(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!lockedOrder.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

        BigDecimal releaseAmount = lockedOrder.releasableAmount();
        walletOrderOperationService.releaseOrderLock(
                currentUserId,
                lockedOrder.getLockedAsset(),
                releaseAmount,
                orderId,
                LedgerType.ORDER_CANCEL_RELEASE,
                ErrorCode.INSUFFICIENT_BALANCE
        );

        lockedOrder.cancel();
        eventRecorder.recordOrderCanceled(lockedOrder, lockedOrder.getLockedAsset(), releaseAmount.toPlainString());
        registerOrderBookCancelAfterCommit(lockedOrder);

        return CancelOrderResponse.of(lockedOrder, lockedOrder.getLockedAsset(), releaseAmount.toPlainString());
    }

    private void registerOrderBookCancelAfterCommit(Order canceledOrder) {
        String marketSymbol = canceledOrder.getMarketSymbol();
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
    }
}
