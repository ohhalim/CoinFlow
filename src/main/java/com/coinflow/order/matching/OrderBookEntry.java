package com.coinflow.order.matching;

import com.coinflow.order.domain.Order;

import java.math.BigDecimal;

public record OrderBookEntry(
        Long orderId,
        Long userId,
        BigDecimal price,
        BigDecimal remainingQuantity,
        Long sequence
) {
    public static OrderBookEntry from(Order order) {
        return new OrderBookEntry(
                order.getId(),
                order.getUserId(),
                order.getPrice(),
                order.getRemainingQuantity(),
                order.getSequence()
        );
    }
}
