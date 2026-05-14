package com.coinflow.event.domain;

public enum DomainEventType {
    ORDER_ACCEPTED,
    ORDER_PARTIALLY_FILLED,
    ORDER_FILLED,
    ORDER_CANCELED,
    TRADE_CREATED,
    SETTLEMENT_COMPLETED
}
