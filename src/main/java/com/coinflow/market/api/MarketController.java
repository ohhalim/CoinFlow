package com.coinflow.market.api;

import com.coinflow.market.domain.MarketStatus;
import com.coinflow.market.dto.MarketResponse;
import com.coinflow.market.dto.OrderBookResponse;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.matching.MatchingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/markets")
public class MarketController {

    private final MarketRepository marketRepository;
    private final MatchingEngine matchingEngine;

    @GetMapping
    public List<MarketResponse> getMarkets() {
        return marketRepository.findAllByStatus(MarketStatus.ACTIVE)
                .stream()
                .map(MarketResponse::from)
                .toList();
    }

    @GetMapping("/{market}/orderbook")
    public OrderBookResponse getOrderBook(@PathVariable String market) {
        return OrderBookResponse.of(
                market,
                matchingEngine.getBuySide(market),
                matchingEngine.getSellSide(market)
        );
    }
}
