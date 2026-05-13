package com.coinflow.order.dto;

import com.coinflow.order.domain.Order;
import com.coinflow.trade.domain.Trade;

import java.time.LocalDateTime;
import java.util.List;

public record CreateOrderResponse(
        Long orderId,
        String clientOrderId,
        String market,
        String side,
        String type,
        String timeInForce,
        String price,
        String originalQuantity,
        String executedQuantity,
        String remainingQuantity,
        String executedQuoteAmount,
        String lockedAsset,
        String lockedAmount,
        String status,
        LocalDateTime createdAt,
        List<TradeResult> trades
) {
    public static CreateOrderResponse of(Order order, List<Trade> trades) {
        List<TradeResult> tradeResults = trades.stream()
                .map(t -> new TradeResult(
                        t.getId(),
                        t.getPrice().toPlainString(),
                        t.getQuantity().toPlainString(),
                        t.getQuoteAmount().toPlainString(),
                        t.getMakerOrderId().equals(order.getId()) ? "MAKER" : "TAKER",
                        t.getTradedAt()
                ))
                .toList();
        return new CreateOrderResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getMarketSymbol(),
                order.getSide().name(),
                order.getType().name(),
                order.getTimeInForce().name(),
                order.getPrice().toPlainString(),
                order.getOriginalQuantity().toPlainString(),
                order.getExecutedQuantity().toPlainString(),
                order.getRemainingQuantity().toPlainString(),
                order.getExecutedQuoteAmount().toPlainString(),
                order.getLockedAsset(),
                order.getLockedAmount().toPlainString(),
                order.getStatus().name(),
                order.getCreatedAt(),
                tradeResults
        );
    }

    public record TradeResult(
            Long tradeId,
            String price,
            String quantity,
            String quoteAmount,
            String liquidity,
            LocalDateTime tradedAt
    ) {
    }
}
