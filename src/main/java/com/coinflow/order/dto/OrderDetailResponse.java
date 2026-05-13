package com.coinflow.order.dto;

import com.coinflow.order.domain.Order;

import java.time.LocalDateTime;

public record OrderDetailResponse(
        Long orderId,
        String clientOrderId,
        String market,
        String side,
        String type,
        String timeInForce,
        String price,
        String originalQuantity,
        String executedQuantity,
        String remainingQuantity,
        String executedQuoteAmount,
        String lockedAsset,
        String lockedAmount,
        String status,
        LocalDateTime createdAt,
        LocalDateTime closedAt
) {
    public static OrderDetailResponse from(Order order) {
        return new OrderDetailResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getMarketSymbol(),
                order.getSide().name(),
                order.getType().name(),
                order.getTimeInForce().name(),
                order.getPrice().toPlainString(),
                order.getOriginalQuantity().toPlainString(),
                order.getExecutedQuantity().toPlainString(),
                order.getRemainingQuantity().toPlainString(),
                order.getExecutedQuoteAmount().toPlainString(),
                order.getLockedAsset(),
                order.getLockedAmount().toPlainString(),
                order.getStatus().name(),
                order.getCreatedAt(),
                order.getClosedAt()
        );
    }
}
