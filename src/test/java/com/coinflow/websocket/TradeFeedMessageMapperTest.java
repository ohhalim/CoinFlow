package com.coinflow.websocket;

import com.coinflow.websocket.dto.TradeFeedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TradeFeedMessageMapperTest {

    private final TradeFeedMessageMapper mapper = new TradeFeedMessageMapper(new ObjectMapper());

    @Test
    void TRADE_CREATED_Kafka_메시지를_체결피드_메시지로_변환한다() throws Exception {
        Optional<TradeFeedMessage> result = mapper.fromKafkaMessage(tradeCreatedMessage(10L, 100L, 101L, 101L));

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(new TradeFeedMessage(
                10L,
                "BTC-KRW",
                "100000000",
                "0.0001",
                "SELL",
                "2026-05-18T10:00:00Z"
        ));
    }

    @Test
    void taker가_buy_order이면_side는_BUY이다() throws Exception {
        Optional<TradeFeedMessage> result = mapper.fromKafkaMessage(tradeCreatedMessage(11L, 100L, 101L, 100L));

        assertThat(result).isPresent();
        assertThat(result.get().side()).isEqualTo("BUY");
    }

    @Test
    void TRADE_CREATED가_아닌_이벤트는_무시한다() throws Exception {
        Optional<TradeFeedMessage> result = mapper.fromKafkaMessage("""
                {
                  "eventId": 12,
                  "eventType": "SETTLEMENT_COMPLETED",
                  "aggregateType": "TRADE",
                  "aggregateId": 200,
                  "marketId": 1,
                  "marketSymbol": "BTC-KRW",
                  "payload": "{\\"schemaVersion\\":\\"1.0\\",\\"occurredAt\\":\\"2026-05-18T10:00:00Z\\",\\"payload\\":{\\"tradeId\\":200}}"
                }
                """);

        assertThat(result).isEmpty();
    }

    static String tradeCreatedMessage(Long eventId, Long buyOrderId, Long sellOrderId, Long takerOrderId) {
        return """
                {
                  "eventId": %d,
                  "eventType": "TRADE_CREATED",
                  "aggregateType": "TRADE",
                  "aggregateId": 200,
                  "marketId": 1,
                  "marketSymbol": "BTC-KRW",
                  "payload": "{\\"schemaVersion\\":\\"1.0\\",\\"occurredAt\\":\\"2026-05-18T10:00:00Z\\",\\"payload\\":{\\"tradeId\\":200,\\"market\\":\\"BTC-KRW\\",\\"buyOrderId\\":%d,\\"sellOrderId\\":%d,\\"makerOrderId\\":%d,\\"takerOrderId\\":%d,\\"price\\":100000000,\\"quantity\\":0.0001,\\"quoteAmount\\":10000}}"
                }
                """.formatted(eventId, buyOrderId, sellOrderId, buyOrderId, takerOrderId);
    }
}
