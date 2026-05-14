package com.coinflow.trade.dto;

import com.coinflow.trade.domain.Trade;

import java.time.LocalDateTime;

public record FillResponse(
        Long tradeId,
        String market,
        Long orderId,
        String price,
        String quantity,
        String quoteAmount,
        String liquidity,
        LocalDateTime tradedAt
) {
    public static FillResponse of(Trade trade, Long userId) {
        boolean isBuyer = trade.getBuyUserId().equals(userId);
        Long orderId = isBuyer ? trade.getBuyOrderId() : trade.getSellOrderId();
        String liquidity = trade.getMakerOrderId().equals(orderId) ? "MAKER" : "TAKER";
        return new FillResponse(
                trade.getId(),
                trade.getMarketSymbol(),
                orderId,
                trade.getPrice().toPlainString(),
                trade.getQuantity().toPlainString(),
                trade.getQuoteAmount().toPlainString(),
                liquidity,
                trade.getTradedAt()
        );
    }
}
