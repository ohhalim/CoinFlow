package com.coinflow.event.repository;

import com.coinflow.event.domain.DomainEvent;
import com.coinflow.event.domain.DomainEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {
    List<DomainEvent> findAllByAggregateTypeAndAggregateId(String aggregateType, Long aggregateId);
    List<DomainEvent> findAllByEventType(DomainEventType eventType);
    List<DomainEvent> findAllByPublishedFalseOrderByIdAsc();
    List<DomainEvent> findByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(int maxAttempts, Pageable pageable);
}
