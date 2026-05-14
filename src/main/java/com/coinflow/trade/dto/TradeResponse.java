package com.coinflow.trade.dto;

import com.coinflow.trade.domain.Trade;

import java.time.LocalDateTime;

public record TradeResponse(
        Long tradeId,
        String market,
        String price,
        String quantity,
        String quoteAmount,
        LocalDateTime tradedAt
) {
    public static TradeResponse from(Trade trade) {
        return new TradeResponse(
                trade.getId(),
                trade.getMarketSymbol(),
                trade.getPrice().stripTrailingZeros().toPlainString(),
                trade.getQuantity().stripTrailingZeros().toPlainString(),
                trade.getQuoteAmount().stripTrailingZeros().toPlainString(),
                trade.getTradedAt()
        );
    }
}
