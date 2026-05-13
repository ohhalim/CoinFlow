package com.coinflow.order.repository;

import com.coinflow.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    boolean existsByUserIdAndClientOrderId(Long userId, String clientOrderId);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    List<Order> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<Order> findAllByUserIdAndMarketSymbolOrderByCreatedAtDesc(Long userId, String marketSymbol);
}
