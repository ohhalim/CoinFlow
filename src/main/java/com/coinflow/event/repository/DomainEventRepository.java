package com.coinflow.event.repository;

import com.coinflow.event.domain.DomainEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {
}
