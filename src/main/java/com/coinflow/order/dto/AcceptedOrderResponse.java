package com.coinflow.order.dto;

import com.coinflow.order.domain.Order;

import java.time.LocalDateTime;

public record AcceptedOrderResponse(
        Long orderId,
        String clientOrderId,
        String market,
        String side,
        String status,
        LocalDateTime acceptedAt
) {
    public static AcceptedOrderResponse of(Order order) {
        return new AcceptedOrderResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getMarketSymbol(),
                order.getSide().name(),
                order.getStatus().name(),
                order.getCreatedAt()
        );
    }
}
