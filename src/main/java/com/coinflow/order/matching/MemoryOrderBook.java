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

    public List<MatchResult> planMatch(Order taker) {
        PriorityQueue<OrderBookEntry> makerQueue = (taker.getSide() == OrderSide.BUY) ? sellQueue : buyQueue;
        PriorityQueue<OrderBookEntry> simulation = new PriorityQueue<>(makerQueue);

        List<MatchResult> results = new ArrayList<>();
        BigDecimal takerRemaining = taker.getRemainingQuantity();

        while (!simulation.isEmpty() && takerRemaining.compareTo(BigDecimal.ZERO) > 0) {
            OrderBookEntry maker = simulation.peek();

            boolean priceMatches = (taker.getSide() == OrderSide.BUY)
                    ? taker.getPrice().compareTo(maker.price()) >= 0
                    : taker.getPrice().compareTo(maker.price()) <= 0;

            if (!priceMatches) break;

            if (maker.userId().equals(taker.getUserId())) {
                break;
            }

            simulation.poll();

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
                simulation.add(new OrderBookEntry(
                        maker.orderId(), maker.userId(), maker.price(), makerRemaining, maker.sequence()
                ));
            }
        }

        return results;
    }

    public void applyMatchPlan(Order taker, List<MatchResult> plan) {
        PriorityQueue<OrderBookEntry> makerQueue = (taker.getSide() == OrderSide.BUY) ? sellQueue : buyQueue;

        for (MatchResult result : plan) {
            OrderBookEntry matched = makerQueue.stream()
                    .filter(e -> e.orderId().equals(result.makerOrderId()))
                    .findFirst()
                    .orElse(null);
            if (matched == null) continue;

            makerQueue.remove(matched);
            BigDecimal remaining = matched.remainingQuantity().subtract(result.quantity());
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                makerQueue.add(new OrderBookEntry(
                        matched.orderId(), matched.userId(), matched.price(), remaining, matched.sequence()
                ));
            }
        }

        if (taker.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
            add(taker);
        }
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

    public boolean hasSelfTrade(OrderSide takerSide, BigDecimal takerPrice, Long userId) {
        PriorityQueue<OrderBookEntry> makerQueue = (takerSide == OrderSide.BUY) ? sellQueue : buyQueue;
        for (OrderBookEntry maker : makerQueue) {
            boolean priceMatches = (takerSide == OrderSide.BUY)
                    ? takerPrice.compareTo(maker.price()) >= 0
                    : takerPrice.compareTo(maker.price()) <= 0;
            if (priceMatches && maker.userId().equals(userId)) return true;
        }
        return false;
    }

    public List<OrderBookEntry> getBuySide() {
        return buyQueue.stream()
                .sorted(Comparator.comparing(OrderBookEntry::price).reversed()
                        .thenComparing(OrderBookEntry::sequence))
                .toList();
    }

    public List<OrderBookEntry> getSellSide() {
        return sellQueue.stream()
                .sorted(Comparator.comparing(OrderBookEntry::price)
                        .thenComparing(OrderBookEntry::sequence))
                .toList();
    }
}
