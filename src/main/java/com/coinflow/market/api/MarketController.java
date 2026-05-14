package com.coinflow.market.api;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.market.domain.MarketStatus;
import com.coinflow.market.dto.MarketResponse;
import com.coinflow.market.dto.OrderBookResponse;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookEntry;
import com.coinflow.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/markets")
public class MarketController {

    private final MarketRepository marketRepository;
    private final MatchingEngine matchingEngine;
    private final OrderService orderService;

    @GetMapping
    public List<MarketResponse> getMarkets() {
        return marketRepository.findAllByStatus(MarketStatus.ACTIVE)
                .stream()
                .map(MarketResponse::from)
                .toList();
    }

    @GetMapping("/{market}/orderbook")
    public OrderBookResponse getOrderBook(@PathVariable String market) {
        Market found = marketRepository.findBySymbol(market)
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));

        ReentrantLock lock = orderService.getMarketLock(found.getId());
        lock.lock();
        try {
            List<OrderBookEntry> buySide  = matchingEngine.getBuySide(market);
            List<OrderBookEntry> sellSide = matchingEngine.getSellSide(market);
            return OrderBookResponse.of(market, buySide, sellSide);
        } finally {
            lock.unlock();
        }
    }
}
