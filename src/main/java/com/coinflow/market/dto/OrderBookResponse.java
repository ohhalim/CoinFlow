package com.coinflow.market.dto;

import com.coinflow.order.matching.OrderBookEntry;

import java.util.List;

public record OrderBookResponse(
        String market,
        List<PriceLevel> bids,
        List<PriceLevel> asks
) {
    public record PriceLevel(String price, String quantity) {
        public static PriceLevel from(OrderBookEntry entry) {
            return new PriceLevel(
                    entry.price().toPlainString(),
                    entry.remainingQuantity().toPlainString()
            );
        }
    }

    public static OrderBookResponse of(String market, List<OrderBookEntry> buySide, List<OrderBookEntry> sellSide) {
        return new OrderBookResponse(
                market,
                buySide.stream().map(PriceLevel::from).toList(),
                sellSide.stream().map(PriceLevel::from).toList()
        );
    }
}
