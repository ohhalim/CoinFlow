package com.coinflow.order.service.lock;

import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.service.metrics.OrderCreateStageRecorder;

import java.util.concurrent.locks.ReentrantLock;

public final class MarketOrderLockScope {

    private final String marketSymbol;
    private final OrderSide side;
    private final ReentrantLock lock;
    private final long acquiredAt;
    private final OrderCreateStageRecorder stageRecorder;
    private boolean released;

    MarketOrderLockScope(
            String marketSymbol,
            OrderSide side,
            ReentrantLock lock,
            long acquiredAt,
            OrderCreateStageRecorder stageRecorder
    ) {
        this.marketSymbol = marketSymbol;
        this.side = side;
        this.lock = lock;
        this.acquiredAt = acquiredAt;
        this.stageRecorder = stageRecorder;
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        stageRecorder.record(marketSymbol, side, "market_lock_hold", System.nanoTime() - acquiredAt);
        lock.unlock();
    }
}
