package com.coinflow.trade.repository;

import com.coinflow.trade.domain.Trade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findAllByMarketSymbolOrderByTradedAtDesc(String marketSymbol, Pageable pageable);

    @Query("""
            SELECT t FROM Trade t
            WHERE (t.buyUserId = :userId OR t.sellUserId = :userId)
              AND (:market IS NULL OR t.marketSymbol = :market)
              AND (:orderId IS NULL OR (
                    (t.buyUserId = :userId AND t.buyOrderId = :orderId)
                 OR (t.sellUserId = :userId AND t.sellOrderId = :orderId)
              ))
              AND t.id > :lastFillId
            ORDER BY t.id ASC
            """)
    List<Trade> findFills(
            @Param("userId") Long userId,
            @Param("market") String market,
            @Param("orderId") Long orderId,
            @Param("lastFillId") Long lastFillId,
            Pageable pageable
    );
}
