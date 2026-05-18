package com.coinflow.config;

import com.coinflow.event.config.OutboxProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic orderEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.getOrderTopic())
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic tradeEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.getTradeTopic())
                .partitions(4)
                .replicas(1)
                .build();
    }
}
