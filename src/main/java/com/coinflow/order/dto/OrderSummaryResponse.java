package com.coinflow.order.dto;

import com.coinflow.order.domain.Order;

import java.time.LocalDateTime;

public record OrderSummaryResponse(
        Long orderId,
        String clientOrderId,
        String market,
        String side,
        String price,
        String originalQuantity,
        String executedQuantity,
        String remainingQuantity,
        String status,
        LocalDateTime createdAt
) {
    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getMarketSymbol(),
                order.getSide().name(),
                order.getPrice().toPlainString(),
                order.getOriginalQuantity().toPlainString(),
                order.getExecutedQuantity().toPlainString(),
                order.getRemainingQuantity().toPlainString(),
                order.getStatus().name(),
                order.getCreatedAt()
        );
    }
}
