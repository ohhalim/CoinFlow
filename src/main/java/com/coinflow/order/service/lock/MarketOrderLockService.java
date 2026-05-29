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
        return acquire(market.getId(), market.getSymbol(), side);
    }

    public MarketOrderLockScope acquire(Long marketId, String marketSymbol, OrderSide side) {
        ReentrantLock marketLock = marketOrderLockManager.getLock(marketId);
        long marketLockWaitStartedAt = System.nanoTime();
        marketLock.lock();
        long marketLockAcquiredAt = System.nanoTime();
        stageRecorder.record(marketSymbol, side, "market_lock_wait",
                marketLockAcquiredAt - marketLockWaitStartedAt);
        return new MarketOrderLockScope(
                marketSymbol,
                side,
                marketLock,
                marketLockAcquiredAt,
                stageRecorder
        );
    }
}
