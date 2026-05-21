package com.coinflow.websocket;

import com.coinflow.market.dto.OrderBookResponse;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookSnapshot;
import com.coinflow.websocket.dto.KafkaEventMessage;
import com.coinflow.websocket.dto.OrderBookSnapshotMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "coinflow.websocket.orderbook.enabled", havingValue = "true", matchIfMissing = true)
public class OrderBookBroadcaster {

    private static final Set<String> ORDERBOOK_TRIGGER_EVENTS = Set.of(
            "ORDER_ACCEPTED",
            "ORDER_PARTIALLY_FILLED",
            "ORDER_FILLED",
            "ORDER_CANCELED"
    );

    private final SimpMessagingTemplate messagingTemplate;
    private final MatchingEngine matchingEngine;
    private final ObjectMapper objectMapper;
    private final TaskScheduler broadcastTaskScheduler;
    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<Long, PendingOrderBookEvent> pendingEvents = new ConcurrentHashMap<>();
    private final Set<Long> scheduledMarkets = ConcurrentHashMap.newKeySet();

    @Value("${coinflow.websocket.orderbook.depth:20}")
    private int depth;

    @Value("${coinflow.websocket.orderbook.coalesce-delay-ms:200}")
    private long coalesceDelayMillis;

    public OrderBookBroadcaster(
            SimpMessagingTemplate messagingTemplate,
            MatchingEngine matchingEngine,
            ObjectMapper objectMapper,
            @Qualifier("websocketBroadcastTaskScheduler") TaskScheduler broadcastTaskScheduler,
            MeterRegistry meterRegistry
    ) {
        this.messagingTemplate = messagingTemplate;
        this.matchingEngine = matchingEngine;
        this.objectMapper = objectMapper;
        this.broadcastTaskScheduler = broadcastTaskScheduler;
        this.meterRegistry = meterRegistry;
    }

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

            queueBroadcast(event);
        } catch (Exception exception) {
            log.warn("Failed to broadcast orderbook snapshot. rawMessage={}, error={}",
                    rawMessage, exception.getMessage(), exception);
        }
    }

    private void queueBroadcast(KafkaEventMessage event) {
        PendingOrderBookEvent pendingEvent = new PendingOrderBookEvent(
                event.eventId(),
                event.marketId(),
                event.marketSymbol()
        );
        pendingEvents.put(event.marketId(), pendingEvent);
        meterRegistry.counter("websocket.orderbook.broadcast.queued", "market", event.marketSymbol()).increment();

        if (scheduledMarkets.add(event.marketId())) {
            scheduleFlush(event.marketId());
        } else {
            meterRegistry.counter("websocket.orderbook.broadcast.coalesced", "market", event.marketSymbol()).increment();
        }
    }

    private void scheduleFlush(Long marketId) {
        broadcastTaskScheduler.schedule(
                () -> flush(marketId),
                Instant.now().plusMillis(Math.max(0, coalesceDelayMillis))
        );
    }

    private void flush(Long marketId) {
        PendingOrderBookEvent event = pendingEvents.remove(marketId);
        scheduledMarkets.remove(marketId);
        if (event == null) {
            return;
        }

        broadcast(event);

        if (pendingEvents.containsKey(marketId) && scheduledMarkets.add(marketId)) {
            scheduleFlush(marketId);
        }
    }

    private void broadcast(PendingOrderBookEvent event) {
        long startedAt = System.nanoTime();
        OrderBookSnapshot snapshot = snapshotOrderBook(event);

        OrderBookResponse response = OrderBookResponse.of(
                event.marketSymbol(),
                snapshot.buySide(),
                snapshot.sellSide(),
                normalizedDepth()
        );

        OrderBookSnapshotMessage message = new OrderBookSnapshotMessage(
                event.eventId(),
                response.market(),
                toPriceLevels(response.bids()),
                toPriceLevels(response.asks())
        );
        try {
            messagingTemplate.convertAndSend("/topic/orderbook/" + message.market(), message);
            meterRegistry.counter("websocket.orderbook.broadcast.sent", "market", message.market()).increment();
        } finally {
            meterRegistry.timer("websocket.orderbook.broadcast.duration", "market", event.marketSymbol())
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private OrderBookSnapshot snapshotOrderBook(PendingOrderBookEvent event) {
        long startedAt = System.nanoTime();
        try {
            return matchingEngine.snapshot(event.marketSymbol());
        } finally {
            meterRegistry.timer("websocket.orderbook.snapshot.duration", "market", event.marketSymbol())
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
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

    private record PendingOrderBookEvent(
            Long eventId,
            Long marketId,
            String marketSymbol
    ) {
    }
}
