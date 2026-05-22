package com.coinflow.order.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class MarketOrderLockManager {

    private final Map<Long, ReentrantLock> marketLocks = new ConcurrentHashMap<>();

    public ReentrantLock getLock(Long marketId) {
        return marketLocks.computeIfAbsent(marketId, key -> new ReentrantLock());
    }
}
