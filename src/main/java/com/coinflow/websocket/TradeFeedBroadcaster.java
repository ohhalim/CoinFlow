package com.coinflow.websocket;

import com.coinflow.websocket.dto.TradeFeedMessage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "coinflow.websocket.trade-feed.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class TradeFeedBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final TradeFeedMessageMapper messageMapper;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${coinflow.outbox.trade-topic:coinflow.trade.events}",
            groupId = "${spring.kafka.consumer.group-id:coinflow-websocket}"
    )
    public void onTradeEvent(String rawMessage) {
        try {
            messageMapper.fromKafkaMessage(rawMessage)
                    .ifPresent(this::broadcast);
        } catch (Exception exception) {
            log.warn("Failed to broadcast trade event. rawMessage={}, error={}",
                    rawMessage, exception.getMessage(), exception);
        }
    }

    private void broadcast(TradeFeedMessage message) {
        long startedAt = System.nanoTime();
        try {
            messagingTemplate.convertAndSend("/topic/trades/" + message.market(), message);
            meterRegistry.counter("websocket.trade.broadcast.sent", "market", message.market()).increment();
        } finally {
            meterRegistry.timer("websocket.trade.broadcast.duration", "market", message.market())
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
