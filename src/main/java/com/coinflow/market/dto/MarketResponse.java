package com.coinflow.market.dto;

import com.coinflow.market.domain.Market;

public record MarketResponse(
        String symbol,
        String displayName,
        String baseAsset,
        String quoteAsset,
        String tickSize,
        String stepSize,
        String minOrderQuantity,
        String minOrderAmount,
        String status
) {
    public static MarketResponse from(Market market) {
        return new MarketResponse(
                market.getSymbol(),
                market.getDisplayName(),
                market.getBaseAsset(),
                market.getQuoteAsset(),
                market.getTickSize().toPlainString(),
                market.getStepSize().toPlainString(),
                market.getMinOrderQuantity().toPlainString(),
                market.getMinOrderAmount().toPlainString(),
                market.getStatus().name()
        );
    }
}
