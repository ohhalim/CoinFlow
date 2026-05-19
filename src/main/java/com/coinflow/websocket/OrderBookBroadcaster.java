package com.coinflow.websocket;

import com.coinflow.market.dto.OrderBookResponse;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.service.OrderService;
import com.coinflow.websocket.dto.KafkaEventMessage;
import com.coinflow.websocket.dto.OrderBookSnapshotMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@ConditionalOnProperty(name = "coinflow.websocket.orderbook.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderBookBroadcaster {

    private static final Set<String> ORDERBOOK_TRIGGER_EVENTS = Set.of(
            "ORDER_ACCEPTED",
            "ORDER_PARTIALLY_FILLED",
            "ORDER_FILLED",
            "ORDER_CANCELED"
    );

    private final SimpMessagingTemplate messagingTemplate;
    private final MatchingEngine matchingEngine;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    @Value("${coinflow.websocket.orderbook.depth:20}")
    private int depth;

    @KafkaListener(
            topics = "${coinflow.outbox.order-topic:coinflow.order.events}",
            groupId = "${spring.kafka.consumer.group-id:coinflow-websocket}"
    )
    public void onOrderEvent(String rawMessage) {
        try {
            KafkaEventMessage event = objectMapper.readValue(rawMessage, KafkaEventMessage.class);
            if (!ORDERBOOK_TRIGGER_EVENTS.contains(event.eventType())) {
                return;
            }

            broadcast(event);
        } catch (Exception exception) {
            log.warn("Failed to broadcast orderbook snapshot. rawMessage={}, error={}",
                    rawMessage, exception.getMessage(), exception);
        }
    }

    private void broadcast(KafkaEventMessage event) {
        ReentrantLock lock = orderService.getMarketLock(event.marketId());
        lock.lock();
        try {
            OrderBookResponse response = OrderBookResponse.of(
                    event.marketSymbol(),
                    matchingEngine.getBuySide(event.marketSymbol()),
                    matchingEngine.getSellSide(event.marketSymbol()),
                    normalizedDepth()
            );

            OrderBookSnapshotMessage message = new OrderBookSnapshotMessage(
                    event.eventId(),
                    response.market(),
                    toPriceLevels(response.bids()),
                    toPriceLevels(response.asks())
            );
            messagingTemplate.convertAndSend("/topic/orderbook/" + message.market(), message);
        } finally {
            lock.unlock();
        }
    }

    private int normalizedDepth() {
        return Math.min(100, Math.max(1, depth));
    }

    private List<OrderBookSnapshotMessage.PriceLevel> toPriceLevels(List<OrderBookResponse.PriceLevel> levels) {
        return levels.stream()
                .map(level -> new OrderBookSnapshotMessage.PriceLevel(level.price(), level.quantity()))
                .toList();
    }
}
