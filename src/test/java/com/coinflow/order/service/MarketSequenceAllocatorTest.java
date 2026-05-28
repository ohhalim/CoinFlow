package com.coinflow.order.service;

import com.coinflow.order.domain.OrderSequence;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.repository.OrderSequenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketSequenceAllocatorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderSequenceRepository orderSequenceRepository;

    @Test
    void usesGreaterValueBetweenOrderMaxSequenceAndStoredSequence() {
        OrderSequence orderSequence = mock(OrderSequence.class);
        when(orderRepository.findMaxSequenceByMarketId(1L)).thenReturn(12L);
        when(orderSequenceRepository.findById(1L)).thenReturn(Optional.of(orderSequence));
        when(orderSequence.getLastSequence()).thenReturn(10L);

        MarketSequenceAllocator allocator = new MarketSequenceAllocator(orderRepository, orderSequenceRepository);

        assertThat(allocator.nextSequence(1L)).isEqualTo(13L);
        assertThat(allocator.nextSequence(1L)).isEqualTo(14L);
        verify(orderRepository, times(1)).findMaxSequenceByMarketId(1L);
        verify(orderSequenceRepository, times(1)).findById(1L);
    }

    @Test
    void fallsBackToStoredSequenceWhenOrderTableIsEmpty() {
        OrderSequence orderSequence = mock(OrderSequence.class);
        when(orderRepository.findMaxSequenceByMarketId(1L)).thenReturn(0L);
        when(orderSequenceRepository.findById(1L)).thenReturn(Optional.of(orderSequence));
        when(orderSequence.getLastSequence()).thenReturn(20L);

        MarketSequenceAllocator allocator = new MarketSequenceAllocator(orderRepository, orderSequenceRepository);

        assertThat(allocator.nextSequence(1L)).isEqualTo(21L);
    }

    @Test
    void allocatesUniqueSequencesUnderConcurrentAccess() throws InterruptedException {
        when(orderRepository.findMaxSequenceByMarketId(1L)).thenReturn(0L);
        when(orderSequenceRepository.findById(1L)).thenReturn(Optional.empty());
        MarketSequenceAllocator allocator = new MarketSequenceAllocator(orderRepository, orderSequenceRepository);
        Set<Long> sequences = ConcurrentHashMap.newKeySet();
        int taskCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(taskCount);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            for (int i = 0; i < taskCount; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        sequences.add(allocator.nextSequence(1L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await();
        } finally {
            executor.shutdown();
        }

        assertThat(sequences).hasSize(taskCount);
        assertThat(sequences).contains(1L, 100L);
        verify(orderRepository, times(1)).findMaxSequenceByMarketId(1L);
        verify(orderSequenceRepository, times(1)).findById(1L);
    }
}
