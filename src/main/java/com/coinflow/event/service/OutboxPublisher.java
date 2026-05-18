package com.coinflow.event.service;

import com.coinflow.event.config.OutboxProperties;
import com.coinflow.event.domain.DomainEvent;
import com.coinflow.event.domain.DomainEventType;
import com.coinflow.event.repository.DomainEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final DomainEventRepository domainEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    @Transactional
    public int publishPendingEvents() {
        List<DomainEvent> events = domainEventRepository
                .findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(
                        properties.getMaxAttempts(),
                        PageRequest.of(0, properties.getBatchSize())
                );

        int publishedCount = 0;
        for (DomainEvent event : events) {
            if (publishOne(event)) {
                publishedCount += 1;
            }
        }
        return publishedCount;
    }

    private boolean publishOne(DomainEvent event) {
        try {
            String topic = resolveTopic(event.getEventType());
            String message = buildMessage(event);

            kafkaTemplate
                    .send(topic, event.getMarketSymbol(), message)
                    .get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);

            event.markPublished();
            return true;
        } catch (Exception exception) {
            event.markPublishFailed(exception.getMessage());
            log.warn(
                    "Kafka outbox publish failed. eventId={}, eventType={}, attempts={}, error={}",
                    event.getId(),
                    event.getEventType(),
                    event.getPublishAttempts(),
                    exception.getMessage()
            );
            return false;
        }
    }

    private String resolveTopic(DomainEventType type) {
        return switch (type) {
            case ORDER_ACCEPTED, ORDER_PARTIALLY_FILLED, ORDER_FILLED, ORDER_CANCELED ->
                    properties.getOrderTopic();
            case TRADE_CREATED, SETTLEMENT_COMPLETED ->
                    properties.getTradeTopic();
        };
    }

    private String buildMessage(DomainEvent event) throws JsonProcessingException {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", event.getId());
        message.put("eventType", event.getEventType().name());
        message.put("aggregateType", event.getAggregateType());
        message.put("aggregateId", event.getAggregateId());
        message.put("marketId", event.getMarketId());
        message.put("marketSymbol", event.getMarketSymbol());
        message.put("payload", event.getPayload());
        return objectMapper.writeValueAsString(message);
    }
}
