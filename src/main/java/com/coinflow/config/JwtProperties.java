package com.coinflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coinflow.jwt")
public record JwtProperties(
        String secret,
        long expiresInSeconds
) { 
}