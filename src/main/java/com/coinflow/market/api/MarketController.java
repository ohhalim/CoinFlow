package com.coinflow.market.api;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.market.domain.MarketStatus;
import com.coinflow.market.dto.MarketResponse;
import com.coinflow.market.dto.OrderBookResponse;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookSnapshot;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/markets")
@Validated
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
    public OrderBookResponse getOrderBook(
            @PathVariable String market,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int depth
    ) {
        Market found = marketRepository.findBySymbol(market)
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));

        OrderBookSnapshot snapshot = matchingEngine.snapshot(found.getSymbol());
        return OrderBookResponse.of(market, snapshot.buySide(), snapshot.sellSide(), depth);
    }
}
