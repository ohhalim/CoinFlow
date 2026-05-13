package com.coinflow.order.repository;

import com.coinflow.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    boolean existsByUserIdAndClientOrderId(Long userId, String clientOrderId);
}
