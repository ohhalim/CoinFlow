package com.coinflow.websocket;

import com.coinflow.websocket.dto.KafkaEventMessage;
import com.coinflow.websocket.dto.TradeFeedMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TradeFeedMessageMapper {

    private static final String TRADE_CREATED = "TRADE_CREATED";

    private final ObjectMapper objectMapper;

    public Optional<TradeFeedMessage> fromKafkaMessage(String rawMessage) throws JsonProcessingException {
        KafkaEventMessage event = objectMapper.readValue(rawMessage, KafkaEventMessage.class);
        if (!TRADE_CREATED.equals(event.eventType())) {
            return Optional.empty();
        }

        JsonNode envelope = objectMapper.readTree(event.payload());
        JsonNode payload = envelope.get("payload");

        long takerOrderId = payload.get("takerOrderId").asLong();
        long buyOrderId = payload.get("buyOrderId").asLong();
        String side = takerOrderId == buyOrderId ? "BUY" : "SELL";

        return Optional.of(new TradeFeedMessage(
                event.eventId(),
                event.marketSymbol(),
                decimalText(payload.get("price")),
                decimalText(payload.get("quantity")),
                side,
                envelope.get("occurredAt").asText()
        ));
    }

    private String decimalText(JsonNode node) {
        BigDecimal decimal = node.decimalValue().stripTrailingZeros();
        return decimal.toPlainString();
    }
}
