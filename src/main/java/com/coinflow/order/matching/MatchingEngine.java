package com.coinflow.order.matching;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MatchingEngine {

    private final Map<String, MemoryOrderBook> orderBooks = new ConcurrentHashMap<>();

    public List<MatchResult> planMatch(Market market, Order taker) {
        MemoryOrderBook book = orderBooks.computeIfAbsent(
                market.getSymbol(),
                k -> new MemoryOrderBook(market.getAmountScale())
        );
        return book.planMatch(taker);
    }

    public List<MatchResult> planMatchRejectingSelfTrade(Market market, Order taker) {
        MemoryOrderBook book = orderBooks.computeIfAbsent(
                market.getSymbol(),
                k -> new MemoryOrderBook(market.getAmountScale())
        );
        try {
            return book.planMatchRejectingSelfTrade(taker);
        } catch (SelfTradeDetectedException e) {
            throw new ApiException(ErrorCode.SELF_TRADE_NOT_ALLOWED);
        }
    }

    public void applyMatchPlan(Market market, Order taker, List<MatchResult> plan) {
        MemoryOrderBook book = orderBooks.computeIfAbsent(
                market.getSymbol(),
                k -> new MemoryOrderBook(market.getAmountScale())
        );
        book.applyMatchPlan(taker, plan);
    }

    public void cancelOrder(String marketSymbol, Order order) {
        MemoryOrderBook book = orderBooks.get(marketSymbol);
        if (book != null) {
            book.remove(order.getId(), order.getSide());
        }
    }

    public void addToBook(Market market, Order order) {
        MemoryOrderBook book = orderBooks.computeIfAbsent(
                market.getSymbol(),
                k -> new MemoryOrderBook(market.getAmountScale())
        );
        book.add(order);
    }

    public void rebuildBook(Market market, List<Order> orders) {
        MemoryOrderBook book = new MemoryOrderBook(market.getAmountScale());
        orders.forEach(book::add);
        orderBooks.put(market.getSymbol(), book);
    }

    public boolean hasSelfTrade(String marketSymbol, OrderSide side, BigDecimal price, Long userId) {
        MemoryOrderBook book = orderBooks.get(marketSymbol);
        return book != null && book.hasSelfTrade(side, price, userId);
    }

    public List<OrderBookEntry> getBuySide(String marketSymbol) {
        MemoryOrderBook book = orderBooks.get(marketSymbol);
        return book != null ? book.getBuySide() : List.of();
    }

    public List<OrderBookEntry> getSellSide(String marketSymbol) {
        MemoryOrderBook book = orderBooks.get(marketSymbol);
        return book != null ? book.getSellSide() : List.of();
    }

    public OrderBookSnapshot snapshot(String marketSymbol) {
        MemoryOrderBook book = orderBooks.get(marketSymbol);
        return book != null ? book.snapshot() : OrderBookSnapshot.empty();
    }

    public void clearAll() {
        orderBooks.clear();
    }
}
