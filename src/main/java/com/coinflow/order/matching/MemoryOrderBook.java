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

    public synchronized List<MatchResult> planMatch(Order taker) {
        return buildMatchPlan(taker);
    }

    public synchronized List<MatchResult> planMatchRejectingSelfTrade(Order taker) {
        PriorityQueue<OrderBookEntry> makerQueue = (taker.getSide() == OrderSide.BUY) ? sellQueue : buyQueue;
        if (hasSelfTradeCandidate(makerQueue, taker.getSide(), taker.getPrice(), taker.getUserId())) {
            throw new SelfTradeDetectedException();
        }
        return buildMatchPlan(taker);
    }

    private List<MatchResult> buildMatchPlan(Order taker) {
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

            // PRD 9절: zero-quote 체결은 만들지 않고 매칭 중단
            if (matchedQuoteAmount.signum() == 0) break;

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

    public synchronized void applyMatchPlan(Order taker, List<MatchResult> plan) {
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

    public synchronized void add(Order order) {
        OrderBookEntry entry = OrderBookEntry.from(order);
        if (order.getSide() == OrderSide.BUY) {
            buyQueue.add(entry);
        } else {
            sellQueue.add(entry);
        }
    }

    public synchronized void remove(Long orderId, OrderSide side) {
        PriorityQueue<OrderBookEntry> queue = (side == OrderSide.BUY) ? buyQueue : sellQueue;
        queue.removeIf(e -> e.orderId().equals(orderId));
    }

    public synchronized boolean hasSelfTrade(OrderSide takerSide, BigDecimal takerPrice, Long userId) {
        PriorityQueue<OrderBookEntry> makerQueue = (takerSide == OrderSide.BUY) ? sellQueue : buyQueue;
        return hasSelfTradeCandidate(makerQueue, takerSide, takerPrice, userId);
    }

    private boolean hasSelfTradeCandidate(
            PriorityQueue<OrderBookEntry> makerQueue,
            OrderSide takerSide,
            BigDecimal takerPrice,
            Long userId
    ) {
        for (OrderBookEntry maker : makerQueue) {
            boolean priceMatches = (takerSide == OrderSide.BUY)
                    ? takerPrice.compareTo(maker.price()) >= 0
                    : takerPrice.compareTo(maker.price()) <= 0;
            if (priceMatches && maker.userId().equals(userId)) return true;
        }
        return false;
    }

    public synchronized List<OrderBookEntry> getBuySide() {
        return sortedBuySide();
    }

    public synchronized List<OrderBookEntry> getSellSide() {
        return sortedSellSide();
    }

    public synchronized OrderBookSnapshot snapshot() {
        return new OrderBookSnapshot(sortedBuySide(), sortedSellSide());
    }

    private List<OrderBookEntry> sortedBuySide() {
        return buyQueue.stream()
                .sorted(Comparator.comparing(OrderBookEntry::price).reversed()
                        .thenComparing(OrderBookEntry::sequence))
                .toList();
    }

    private List<OrderBookEntry> sortedSellSide() {
        return sellQueue.stream()
                .sorted(Comparator.comparing(OrderBookEntry::price)
                        .thenComparing(OrderBookEntry::sequence))
                .toList();
    }
}

class SelfTradeDetectedException extends RuntimeException {
}
