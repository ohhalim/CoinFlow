package com.coinflow.order.service.lock;

import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.service.metrics.OrderCreateStageRecorder;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

@Service
public class MarketOrderLockService {

    private final MarketOrderLockManager marketOrderLockManager;
    private final OrderCreateStageRecorder stageRecorder;

    public MarketOrderLockService(
            MarketOrderLockManager marketOrderLockManager,
            OrderCreateStageRecorder stageRecorder
    ) {
        this.marketOrderLockManager = marketOrderLockManager;
        this.stageRecorder = stageRecorder;
    }

    public MarketOrderLockScope acquire(Market market, OrderSide side) {
        ReentrantLock marketLock = marketOrderLockManager.getLock(market.getId());
        long marketLockWaitStartedAt = System.nanoTime();
        marketLock.lock();
        long marketLockAcquiredAt = System.nanoTime();
        stageRecorder.record(market.getSymbol(), side, "market_lock_wait",
                marketLockAcquiredAt - marketLockWaitStartedAt);
        return new MarketOrderLockScope(
                market.getSymbol(),
                side,
                marketLock,
                marketLockAcquiredAt,
                stageRecorder
        );
    }

    public ReentrantLock getLock(Long marketId) {
        return marketOrderLockManager.getLock(marketId);
    }
}
