package com.coinflow.websocket.dto;

public record TradeFeedMessage(
        Long eventId,
        String market,
        String price,
        String quantity,
        String side,
        String tradedAt
) {
}
