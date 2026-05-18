package com.coinflow.event.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coinflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublishingScheduler {

    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelayString = "${coinflow.outbox.fixed-delay-ms:1000}")
    public void publish() {
        outboxPublisher.publishPendingEvents();
    }
}
