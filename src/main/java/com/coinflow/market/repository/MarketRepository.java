package com.coinflow.market.repository;

import com.coinflow.market.domain.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarketRepository extends JpaRepository<Market, Long> {
    Optional<Market> findBySymbol(String symbol);
    List<Market> findAllByStatusNot(com.coinflow.market.domain.MarketStatus status);
    List<Market> findAllByStatus(com.coinflow.market.domain.MarketStatus status);
}
