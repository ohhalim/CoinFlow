package com.coinflow.order.repository;

import com.coinflow.order.domain.OrderSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderSequenceRepository extends JpaRepository<OrderSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT os FROM OrderSequence os WHERE os.marketId = :marketId")
    Optional<OrderSequence> findByMarketIdWithLock(@Param("marketId") Long marketId);
}
