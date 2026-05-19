package com.coinflow.websocket.dto;

public record KafkaEventMessage(
        Long eventId,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Long marketId,
        String marketSymbol,
        String payload
) {
}
