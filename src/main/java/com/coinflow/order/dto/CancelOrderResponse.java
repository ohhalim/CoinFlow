package com.coinflow.order.dto;

import com.coinflow.order.domain.Order;

import java.time.LocalDateTime;

public record CancelOrderResponse(
        Long orderId,
        String market,
        String status,
        String releasedAsset,
        String releasedAmount,
        LocalDateTime canceledAt
) {
    public static CancelOrderResponse of(Order order, String releasedAsset, String releasedAmount) {
        return new CancelOrderResponse(
                order.getId(),
                order.getMarketSymbol(),
                order.getStatus().name(),
                releasedAsset,
                releasedAmount,
                order.getClosedAt()
        );
    }
}
