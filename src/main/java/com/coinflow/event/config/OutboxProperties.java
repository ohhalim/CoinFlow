package com.coinflow.event.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "coinflow.outbox")
public class OutboxProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 1000;
    private int maxAttempts = 5;
    private int batchSize = 100;
    private long sendTimeoutMs = 5000;
    private String orderTopic = "coinflow.order.events";
    private String tradeTopic = "coinflow.trade.events";
}
