package com.coinflow.order.service;

import com.coinflow.order.domain.OrderSide;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class OrderCreateStageRecorder {

    private static final String ORDER_CREATE_STAGE_TIMER = "order.create.stage.duration";

    private final MeterRegistry meterRegistry;

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
        meterRegistry.timer(
                ORDER_CREATE_STAGE_TIMER,
                "market", marketSymbol,
                "side", side.name(),
                "stage", stage
        ).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
