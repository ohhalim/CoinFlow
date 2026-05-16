package com.coinflow.market.dto;

import com.coinflow.market.domain.Market;

public record MarketResponse(
        String market,
        String displayName,
        String baseAsset,
        String quoteAsset,
        int amountScale,
        String tickSize,
        String stepSize,
        String minOrderQuantity,
        String minOrderAmount,
        String status,
        boolean cancelOnly
) {
    public static MarketResponse from(Market market) {
        return new MarketResponse(
                market.getSymbol(),
                market.getDisplayName(),
                market.getBaseAsset(),
                market.getQuoteAsset(),
                market.getAmountScale(),
                market.getTickSize().toPlainString(),
                market.getStepSize().toPlainString(),
                market.getMinOrderQuantity().toPlainString(),
                market.getMinOrderAmount().toPlainString(),
                market.getStatus().name(),
                market.isCancelOnly()
        );
    }
}
