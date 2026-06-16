package com.coinflow.market.dto;

import com.coinflow.order.matching.OrderBookEntry;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record OrderBookResponse(
        String market,
        List<PriceLevel> bids,
        List<PriceLevel> asks
) {
    public record PriceLevel(String price, String quantity) {
    }

    public static OrderBookResponse of(String market, List<OrderBookEntry> buySide, List<OrderBookEntry> sellSide, int depth) {
        return new OrderBookResponse(
                market,
                aggregate(buySide, Comparator.reverseOrder(), depth),
                aggregate(sellSide, Comparator.naturalOrder(), depth)
        );
    }

    private static List<PriceLevel> aggregate(
            List<OrderBookEntry> entries,
            Comparator<BigDecimal> priceOrder,
            int depth
    ) {
        Map<BigDecimal, BigDecimal> quantitiesByPrice = entries.stream()
                .collect(Collectors.groupingBy(
                        OrderBookEntry::price,
                        () -> new TreeMap<>(priceOrder),
                        Collectors.reducing(BigDecimal.ZERO, OrderBookEntry::remainingQuantity, BigDecimal::add)
                ));

        return quantitiesByPrice.entrySet().stream()
                .limit(depth)
                .map(entry -> new PriceLevel(
                        entry.getKey().stripTrailingZeros().toPlainString(),
                        entry.getValue().stripTrailingZeros().toPlainString()
                ))
                .toList();
    }
}
