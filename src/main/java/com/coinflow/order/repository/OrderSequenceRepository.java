package com.coinflow.order.repository;

import com.coinflow.order.domain.OrderSequence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSequenceRepository extends JpaRepository<OrderSequence, Long> {
}
