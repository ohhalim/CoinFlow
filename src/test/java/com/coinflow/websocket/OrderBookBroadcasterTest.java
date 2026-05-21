package com.coinflow.websocket;

import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookEntry;
import com.coinflow.order.service.OrderService;
import com.coinflow.websocket.dto.OrderBookSnapshotMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderBookBroadcasterTest {

    private SimpMessagingTemplate messagingTemplate;
    private MatchingEngine matchingEngine;
    private OrderService orderService;
    private TaskScheduler taskScheduler;
    private OrderBookBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        matchingEngine = mock(MatchingEngine.class);
        orderService = mock(OrderService.class);
        taskScheduler = mock(TaskScheduler.class);
        runScheduledTasksImmediately();
        broadcaster = new OrderBookBroadcaster(
                messagingTemplate,
                matchingEngine,
                orderService,
                new ObjectMapper(),
                taskScheduler,
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(broadcaster, "depth", 20);
        ReflectionTestUtils.setField(broadcaster, "coalesceDelayMillis", 200L);
    }

    @Test
    void 주문_이벤트를_시장별_오더북_topic으로_broadcast한다() {
        when(orderService.getMarketLock(1L)).thenReturn(new ReentrantLock());
        when(matchingEngine.getBuySide("BTC-KRW")).thenReturn(List.of(
                entry(1L, "100000000", "0.0001", 1L),
                entry(2L, "100000000", "0.0002", 2L),
                entry(3L, "99000000", "0.0003", 3L)
        ));
        when(matchingEngine.getSellSide("BTC-KRW")).thenReturn(List.of(
                entry(4L, "101000000", "0.0004", 4L)
        ));

        broadcaster.onOrderEvent(orderEventMessage(30L, "ORDER_ACCEPTED"));

        var captor = org.mockito.ArgumentCaptor.forClass(OrderBookSnapshotMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/orderbook/BTC-KRW"), captor.capture());

        OrderBookSnapshotMessage message = captor.getValue();
        assertThat(message.eventId()).isEqualTo(30L);
        assertThat(message.market()).isEqualTo("BTC-KRW");
        assertThat(message.bids()).containsExactly(
                new OrderBookSnapshotMessage.PriceLevel("100000000", "0.0003"),
                new OrderBookSnapshotMessage.PriceLevel("99000000", "0.0003")
        );
        assertThat(message.asks()).containsExactly(
                new OrderBookSnapshotMessage.PriceLevel("101000000", "0.0004")
        );
    }

    @Test
    void 주문_이벤트가_아니면_broadcast하지_않는다() {
        broadcaster.onOrderEvent(orderEventMessage(31L, "SETTLEMENT_COMPLETED"));

        verifyNoInteractions(messagingTemplate, matchingEngine, orderService);
    }

    @Test
    void 같은_시장_오더북_이벤트는_지연_시간_동안_마지막_snapshot만_broadcast한다() {
        doReturn(null).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        when(orderService.getMarketLock(1L)).thenReturn(new ReentrantLock());
        when(matchingEngine.getBuySide("BTC-KRW")).thenReturn(List.of(
                entry(1L, "100000000", "0.0001", 1L)
        ));
        when(matchingEngine.getSellSide("BTC-KRW")).thenReturn(List.of());

        broadcaster.onOrderEvent(orderEventMessage(32L, "ORDER_ACCEPTED"));
        broadcaster.onOrderEvent(orderEventMessage(33L, "ORDER_CANCELED"));

        var taskCaptor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        verifyNoInteractions(messagingTemplate);

        taskCaptor.getValue().run();

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(OrderBookSnapshotMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/orderbook/BTC-KRW"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().eventId()).isEqualTo(33L);
    }

    @Test
    void 파싱_실패가_발생해도_예외를_전파하지_않는다() {
        broadcaster.onOrderEvent("{broken-json");

        verifyNoInteractions(messagingTemplate, matchingEngine, orderService);
    }

    private static OrderBookEntry entry(Long orderId, String price, String quantity, Long sequence) {
        return new OrderBookEntry(
                orderId,
                1L,
                new BigDecimal(price),
                new BigDecimal(quantity),
                sequence
        );
    }

    private static String orderEventMessage(Long eventId, String eventType) {
        return """
                {
                  "eventId": %d,
                  "eventType": "%s",
                  "aggregateType": "ORDER",
                  "aggregateId": 100,
                  "marketId": 1,
                  "marketSymbol": "BTC-KRW",
                  "payload": "{\\"schemaVersion\\":\\"1.0\\",\\"occurredAt\\":\\"2026-05-19T10:00:00Z\\",\\"payload\\":{\\"orderId\\":100}}"
                }
                """.formatted(eventId, eventType);
    }

    private void runScheduledTasksImmediately() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }
}
