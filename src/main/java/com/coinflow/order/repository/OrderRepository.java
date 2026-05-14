package com.coinflow.order.repository;

import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    boolean existsByUserIdAndClientOrderId(Long userId, String clientOrderId);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);

    List<Order> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<Order> findAllByUserIdAndMarketSymbolOrderByCreatedAtDesc(Long userId, String marketSymbol, Pageable pageable);

    List<Order> findAllByStatusInOrderBySequenceAsc(List<OrderStatus> statuses);
}
