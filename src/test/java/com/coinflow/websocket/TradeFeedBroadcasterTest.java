package com.coinflow.websocket;

import com.coinflow.websocket.dto.TradeFeedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.*;

class TradeFeedBroadcasterTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final TradeFeedBroadcaster broadcaster = new TradeFeedBroadcaster(
            messagingTemplate,
            new TradeFeedMessageMapper(new ObjectMapper()),
            new SimpleMeterRegistry()
    );

    @Test
    void TRADE_CREATED_이벤트를_시장별_topic으로_broadcast한다() {
        broadcaster.onTradeEvent(TradeFeedMessageMapperTest.tradeCreatedMessage(20L, 100L, 101L, 100L));

        verify(messagingTemplate).convertAndSend(
                "/topic/trades/BTC-KRW",
                new TradeFeedMessage(20L, "BTC-KRW", "100000000", "0.0001", "BUY", "2026-05-18T10:00:00Z")
        );
    }

    @Test
    void 체결_생성_이벤트가_아니면_broadcast하지_않는다() {
        broadcaster.onTradeEvent("""
                {
                  "eventId": 21,
                  "eventType": "SETTLEMENT_COMPLETED",
                  "aggregateType": "TRADE",
                  "aggregateId": 200,
                  "marketId": 1,
                  "marketSymbol": "BTC-KRW",
                  "payload": "{\\"schemaVersion\\":\\"1.0\\",\\"occurredAt\\":\\"2026-05-18T10:00:00Z\\",\\"payload\\":{\\"tradeId\\":200}}"
                }
                """);

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void 파싱_실패가_발생해도_예외를_전파하지_않는다() {
        broadcaster.onTradeEvent("{broken-json");

        verifyNoInteractions(messagingTemplate);
    }
}
