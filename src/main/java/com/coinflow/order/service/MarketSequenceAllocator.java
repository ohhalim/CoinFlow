package com.coinflow.order.service;

import com.coinflow.order.domain.OrderSequence;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.repository.OrderSequenceRepository;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MarketSequenceAllocator {

    private final OrderRepository orderRepository;
    private final OrderSequenceRepository orderSequenceRepository;
    private final ConcurrentMap<Long, AtomicLong> sequences = new ConcurrentHashMap<>();

    public MarketSequenceAllocator(
            OrderRepository orderRepository,
            OrderSequenceRepository orderSequenceRepository
    ) {
        this.orderRepository = orderRepository;
        this.orderSequenceRepository = orderSequenceRepository;
    }

    public long nextSequence(Long marketId) {
        return sequences.computeIfAbsent(marketId, this::initializeSequence)
                .incrementAndGet();
    }

    private AtomicLong initializeSequence(Long marketId) {
        Long maxOrderSequence = orderRepository.findMaxSequenceByMarketId(marketId);
        long orderMax = maxOrderSequence == null ? 0L : maxOrderSequence;
        long storedSequence = orderSequenceRepository.findById(marketId)
                .map(OrderSequence::getLastSequence)
                .orElse(0L);
        return new AtomicLong(Math.max(orderMax, storedSequence));
    }
}
