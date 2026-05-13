package com.coinflow.trade.repository;

import com.coinflow.trade.domain.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findAllByMarketSymbolOrderByTradedAtDesc(String marketSymbol);
}
