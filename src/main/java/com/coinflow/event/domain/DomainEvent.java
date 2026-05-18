package com.coinflow.event.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "domain_events")
public class DomainEvent {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private DomainEventType eventType;

    private String aggregateType;
    private Long aggregateId;

    private Long marketId;
    private String marketSymbol;

    private String payload;

    private boolean published;
    private LocalDateTime publishedAt;
    private int publishAttempts;
    private String lastErrorMessage;

    private LocalDateTime createdAt;

    public static DomainEvent create(
            DomainEventType eventType,
            String aggregateType,
            Long aggregateId,
            Long marketId,
            String marketSymbol,
            String payload
    ) {
        DomainEvent event = new DomainEvent();
        event.eventType = eventType;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.marketId = marketId;
        event.marketSymbol = marketSymbol;
        event.payload = payload;
        event.published = false;
        event.publishAttempts = 0;
        event.lastErrorMessage = null;
        event.createdAt = LocalDateTime.now();
        return event;
    }

    public void markPublished() {
        this.published = true;
        this.publishedAt = LocalDateTime.now();
        this.lastErrorMessage = null;
    }

    public void markPublishFailed(String errorMessage) {
        this.publishAttempts += 1;
        this.lastErrorMessage = truncate(errorMessage);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
