package com.coinflow.order.service;

import com.coinflow.order.domain.OrderSide;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class OrderCreateStageRecorder {

    private static final String ORDER_CREATE_STAGE_TIMER = "order.create.stage.duration";

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<StageTimerKey, Timer> timers = new ConcurrentHashMap<>();

    public OrderCreateStageRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T record(String marketSymbol, OrderSide side, String stage, Supplier<T> supplier) {
        long startedAt = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            record(marketSymbol, side, stage, System.nanoTime() - startedAt);
        }
    }

    public void record(String marketSymbol, OrderSide side, String stage, long elapsedNanos) {
        timers.computeIfAbsent(new StageTimerKey(marketSymbol, side, stage), this::createTimer)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private Timer createTimer(StageTimerKey key) {
        return Timer.builder(ORDER_CREATE_STAGE_TIMER)
                .tag("market", key.marketSymbol())
                .tag("side", key.side().name())
                .tag("stage", key.stage())
                .register(meterRegistry);
    }

    private record StageTimerKey(String marketSymbol, OrderSide side, String stage) {
    }
}
