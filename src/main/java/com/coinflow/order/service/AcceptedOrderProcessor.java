package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.matching.MatchResult;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookRecoveryService;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.trade.domain.Trade;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class AcceptedOrderProcessor {

    private final OrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MatchingEngine matchingEngine;
    private final OrderBookRecoveryService orderBookRecoveryService;
    private final DomainEventRecorder eventRecorder;
    private final MarketOrderLockManager marketOrderLockManager;
    private final OrderSettlementService orderSettlementService;
    private final OrderCreateStageRecorder stageRecorder;
    private final TransactionTemplate transactionTemplate;

    public AcceptedOrderProcessor(
            OrderRepository orderRepository,
            WalletRepository walletRepository,
            WalletLedgerRepository walletLedgerRepository,
            MatchingEngine matchingEngine,
            OrderBookRecoveryService orderBookRecoveryService,
            DomainEventRecorder eventRecorder,
            MarketOrderLockManager marketOrderLockManager,
            OrderSettlementService orderSettlementService,
            OrderCreateStageRecorder stageRecorder,
            PlatformTransactionManager transactionManager
    ) {
        this.orderRepository = orderRepository;
        this.walletRepository = walletRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.matchingEngine = matchingEngine;
        this.orderBookRecoveryService = orderBookRecoveryService;
        this.eventRecorder = eventRecorder;
        this.marketOrderLockManager = marketOrderLockManager;
        this.orderSettlementService = orderSettlementService;
        this.stageRecorder = stageRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void processAcceptedOrder(Market market, OrderSide side, Long orderId) {
        try {
            processAcceptedOrderInternal(market, side, orderId);
        } catch (Throwable e) {
            log.warn("Accepted order processing failed. orderId={}, error={}", orderId, e.getMessage(), e);
            rejectAcceptedOrder(orderId, e.getClass().getSimpleName());
        }
    }

    private void processAcceptedOrderInternal(Market market, OrderSide side, Long orderId) {
        MarketLockScope marketLockScope = acquireMarketLock(market, side);
        AtomicBoolean releaseRegistered = new AtomicBoolean(false);

        try {
            transactionTemplate.execute(status -> {
                Order order = orderRepository.findByIdWithLock(orderId)
                        .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
                if (order.getStatus() != OrderStatus.ACCEPTED) {
                    return null;
                }

                order.open();
                List<MatchResult> plan = stageRecorder.record(
                        market.getSymbol(), side, "matching_plan",
                        () -> matchingEngine.planMatchRejectingSelfTrade(market, order)
                );
                List<Order> autoCanceledMakers = new ArrayList<>();
                List<Trade> trades = stageRecorder.record(
                        market.getSymbol(), side, "settlement",
                        () -> orderSettlementService.settle(market, order, plan, autoCanceledMakers, null, false)
                );

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
                            marketLockScope.release();
                        }
                    }

                    @Override
                    public void afterCompletion(int status) {
                        marketLockScope.release();
                    }
                });
                releaseRegistered.set(true);
                return trades;
            });
        } finally {
            if (!releaseRegistered.get()) {
                marketLockScope.release();
            }
        }
    }

    private void rejectAcceptedOrder(Long orderId, String reason) {
        transactionTemplate.execute(status -> {
            Order order = orderRepository.findByIdWithLock(orderId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
            if (order.getStatus() != OrderStatus.ACCEPTED) {
                return null;
            }
            BigDecimal releaseAmount = order.releasableAmount();
            Wallet wallet = walletRepository.findByUserIdAndAssetWithLock(order.getUserId(), order.getLockedAsset())
                    .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND));
            wallet.unlock(releaseAmount);
            walletLedgerRepository.save(WalletLedger.create(
                    wallet,
                    LedgerType.ORDER_REJECT_RELEASE,
                    releaseAmount,
                    releaseAmount.negate(),
                    orderId,
                    null
            ));
            String releasedAsset = order.getLockedAsset();
            order.reject();
            eventRecorder.recordOrderRejected(order, releasedAsset, releaseAmount.toPlainString(), reason);
            return null;
        });
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
}
