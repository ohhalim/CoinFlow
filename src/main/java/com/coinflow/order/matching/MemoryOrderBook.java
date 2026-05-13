package com.coinflow.order.matching;

import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class MemoryOrderBook {

    // BUY: 높은 가격 우선, 같은 가격이면 낮은 sequence 우선
    private final PriorityQueue<OrderBookEntry> buyQueue = new PriorityQueue<>(
            Comparator.comparing(OrderBookEntry::price).reversed()
                    .thenComparing(OrderBookEntry::sequence)
    );

    // SELL: 낮은 가격 우선, 같은 가격이면 낮은 sequence 우선
    private final PriorityQueue<OrderBookEntry> sellQueue = new PriorityQueue<>(
            Comparator.comparing(OrderBookEntry::price)
                    .thenComparing(OrderBookEntry::sequence)
    );

    private final int amountScale;

    public MemoryOrderBook(int amountScale) {
        this.amountScale = amountScale;
    }

    public List<MatchResult> match(Order taker) {
        List<MatchResult> results = new ArrayList<>();
        PriorityQueue<OrderBookEntry> makerQueue = (taker.getSide() == OrderSide.BUY) ? sellQueue : buyQueue;

        BigDecimal takerRemaining = taker.getRemainingQuantity();

        while (!makerQueue.isEmpty() && takerRemaining.compareTo(BigDecimal.ZERO) > 0) {
            OrderBookEntry maker = makerQueue.peek();

            boolean priceMatches = (taker.getSide() == OrderSide.BUY)
                    ? taker.getPrice().compareTo(maker.price()) >= 0
                    : taker.getPrice().compareTo(maker.price()) <= 0;

            if (!priceMatches) break;

            // self trade 방지
            if (maker.userId().equals(taker.getUserId())) {
                break;
            }

            makerQueue.poll();

            BigDecimal matchedQuantity = takerRemaining.min(maker.remainingQuantity());
            BigDecimal matchedQuoteAmount = maker.price()
                    .multiply(matchedQuantity)
                    .setScale(amountScale, RoundingMode.DOWN);

            boolean isTakerBuy = taker.getSide() == OrderSide.BUY;
            results.add(new MatchResult(
                    maker.orderId(),
                    taker.getId(),
                    isTakerBuy ? taker.getId() : maker.orderId(),
                    isTakerBuy ? maker.orderId() : taker.getId(),
                    maker.userId(),
                    taker.getUserId(),
                    isTakerBuy ? taker.getUserId() : maker.userId(),
                    isTakerBuy ? maker.userId() : taker.getUserId(),
                    maker.price(),
                    matchedQuantity,
                    matchedQuoteAmount
            ));

            takerRemaining = takerRemaining.subtract(matchedQuantity);

            BigDecimal makerRemaining = maker.remainingQuantity().subtract(matchedQuantity);
            if (makerRemaining.compareTo(BigDecimal.ZERO) > 0) {
                makerQueue.add(new OrderBookEntry(
                        maker.orderId(), maker.userId(), maker.price(), makerRemaining, maker.sequence()
                ));
            }
        }

        return results;
    }

    public void add(Order order) {
        OrderBookEntry entry = OrderBookEntry.from(order);
        if (order.getSide() == OrderSide.BUY) {
            buyQueue.add(entry);
        } else {
            sellQueue.add(entry);
        }
    }

    public void remove(Long orderId, OrderSide side) {
        PriorityQueue<OrderBookEntry> queue = (side == OrderSide.BUY) ? buyQueue : sellQueue;
        queue.removeIf(e -> e.orderId().equals(orderId));
    }
}
