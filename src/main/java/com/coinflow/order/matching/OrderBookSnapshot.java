package com.coinflow.order.matching;

import java.util.List;

public record OrderBookSnapshot(
        List<OrderBookEntry> buySide,
        List<OrderBookEntry> sellSide
) {
    public static OrderBookSnapshot empty() {
        return new OrderBookSnapshot(List.of(), List.of());
    }
}
