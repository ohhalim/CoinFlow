package com.coinflow.event.service;

import com.coinflow.event.config.OutboxProperties;
import com.coinflow.event.domain.DomainEvent;
import com.coinflow.event.domain.DomainEventType;
import com.coinflow.event.repository.DomainEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock private DomainEventRepository domainEventRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxProperties properties;
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setMaxAttempts(5);
        properties.setBatchSize(100);
        properties.setSendTimeoutMs(1000);
        properties.setOrderTopic("coinflow.order.events");
        properties.setTradeTopic("coinflow.trade.events");

        outboxPublisher = new OutboxPublisher(
                domainEventRepository,
                kafkaTemplate,
                new ObjectMapper(),
                properties
        );
    }

    @Test
    void 미발행_주문_이벤트를_order_topic으로_발행하고_published_처리한다() {
        DomainEvent event = event(1L, DomainEventType.ORDER_ACCEPTED, "ORDER", 10L);
        when(domainEventRepository.findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(eq(5), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(success());

        int publishedCount = outboxPublisher.publishPendingEvents();

        assertThat(publishedCount).isEqualTo(1);
        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getPublishAttempts()).isZero();
        assertThat(event.getLastErrorMessage()).isNull();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), messageCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("coinflow.order.events");
        assertThat(keyCaptor.getValue()).isEqualTo("BTC-KRW");
        assertThat(messageCaptor.getValue())
                .contains("\"eventId\":1")
                .contains("\"eventType\":\"ORDER_ACCEPTED\"")
                .contains("\"aggregateType\":\"ORDER\"")
                .contains("\"aggregateId\":10")
                .contains("\"marketSymbol\":\"BTC-KRW\"")
                .contains("\"payload\":\"{\\\"orderId\\\":10}\"");
    }

    @Test
    void 미발행_체결_이벤트를_trade_topic으로_발행한다() {
        DomainEvent event = event(2L, DomainEventType.TRADE_CREATED, "TRADE", 20L);
        when(domainEventRepository.findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(eq(5), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(success());

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("coinflow.trade.events"), eq("BTC-KRW"), contains("\"eventType\":\"TRADE_CREATED\""));
        assertThat(event.isPublished()).isTrue();
    }

    @Test
    void Kafka_발행_실패_시_주문_트랜잭션과_분리되어_attempt만_증가한다() {
        DomainEvent event = event(3L, DomainEventType.TRADE_CREATED, "TRADE", 30L);
        when(domainEventRepository.findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(eq(5), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failure());

        int publishedCount = outboxPublisher.publishPendingEvents();

        assertThat(publishedCount).isZero();
        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPublishAttempts()).isEqualTo(1);
        assertThat(event.getLastErrorMessage()).contains("Kafka unavailable");
    }

    @Test
    void 한_이벤트_실패가_다음_이벤트_발행을_막지_않는다() {
        DomainEvent failed = event(4L, DomainEventType.TRADE_CREATED, "TRADE", 40L);
        DomainEvent succeeded = event(5L, DomainEventType.TRADE_CREATED, "TRADE", 50L);
        when(domainEventRepository.findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(eq(5), any(Pageable.class)))
                .thenReturn(List.of(failed, succeeded));
        when(kafkaTemplate.send(anyString(), anyString(), contains("\"eventId\":4")))
                .thenReturn(failure());
        when(kafkaTemplate.send(anyString(), anyString(), contains("\"eventId\":5")))
                .thenReturn(success());

        int publishedCount = outboxPublisher.publishPendingEvents();

        assertThat(publishedCount).isEqualTo(1);
        assertThat(failed.isPublished()).isFalse();
        assertThat(failed.getPublishAttempts()).isEqualTo(1);
        assertThat(succeeded.isPublished()).isTrue();
        assertThat(succeeded.getPublishAttempts()).isZero();
    }

    @Test
    void 최대_재시도_미만_이벤트만_배치_크기만큼_조회한다() {
        properties.setMaxAttempts(3);
        properties.setBatchSize(50);
        when(domainEventRepository.findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(eq(3), any(Pageable.class)))
                .thenReturn(List.of());

        int publishedCount = outboxPublisher.publishPendingEvents();

        assertThat(publishedCount).isZero();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(domainEventRepository)
                .findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(eq(3), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
        verifyNoInteractions(kafkaTemplate);
    }

    private DomainEvent event(Long id, DomainEventType eventType, String aggregateType, Long aggregateId) {
        DomainEvent event = DomainEvent.create(
                eventType,
                aggregateType,
                aggregateId,
                1L,
                "BTC-KRW",
                "{\"orderId\":" + aggregateId + "}"
        );
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private CompletableFuture<SendResult<String, String>> success() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SendResult<String, String>> failure() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka unavailable"));
        return future;
    }
}
