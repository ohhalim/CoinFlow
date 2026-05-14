package com.coinflow.trade.api;

import com.coinflow.trade.dto.FillResponse;
import com.coinflow.trade.dto.TradeResponse;
import com.coinflow.trade.repository.TradeRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@Validated
public class TradeController {

    private final TradeRepository tradeRepository;

    @GetMapping("/markets/{market}/trades")
    public List<TradeResponse> getTrades(
            @PathVariable String market,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return tradeRepository.findAllByMarketSymbolOrderByTradedAtDesc(market, PageRequest.of(0, limit))
                .stream()
                .map(TradeResponse::from)
                .toList();
    }

    @GetMapping("/fills")
    public List<FillResponse> getFills(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String market,
            @RequestParam(defaultValue = "0") @Min(0) long lastFillId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        var pageable = PageRequest.of(0, limit);
        var trades = (market != null)
                ? tradeRepository.findAllByUserIdAndMarket(userId, market, lastFillId, pageable)
                : tradeRepository.findAllByUserId(userId, lastFillId, pageable);
        return trades.stream().map(t -> FillResponse.of(t, userId)).toList();
    }
}
