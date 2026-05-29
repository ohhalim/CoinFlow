package com.coinflow.order.service.cancel;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.order.domain.Order;
import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.service.lock.MarketOrderLockService;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class OrderCancelService {

    private final OrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MatchingEngine matchingEngine;
    private final DomainEventRecorder eventRecorder;
    private final MarketOrderLockService marketOrderLockService;
    private final TransactionTemplate transactionTemplate;

    public OrderCancelService(
            OrderRepository orderRepository,
            WalletRepository walletRepository,
            WalletLedgerRepository walletLedgerRepository,
            MatchingEngine matchingEngine,
            DomainEventRecorder eventRecorder,
            MarketOrderLockService marketOrderLockService,
            PlatformTransactionManager transactionManager
    ) {
        this.orderRepository = orderRepository;
        this.walletRepository = walletRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.matchingEngine = matchingEngine;
        this.eventRecorder = eventRecorder;
        this.marketOrderLockService = marketOrderLockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CancelOrderResponse cancelOrder(Long currentUserId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

        ReentrantLock marketLock = marketOrderLockService.getLock(order.getMarketId());
        marketLock.lock();
        try {
            return transactionTemplate.execute(status -> cancelInTransaction(currentUserId, orderId));
        } finally {
            marketLock.unlock();
        }
    }

    private CancelOrderResponse cancelInTransaction(Long currentUserId, Long orderId) {
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
