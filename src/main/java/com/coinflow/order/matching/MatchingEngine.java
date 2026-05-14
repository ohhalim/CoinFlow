package com.coinflow.order.matching;

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

    public List<MatchResult> match(Market market, Order taker) {
        MemoryOrderBook book = orderBooks.computeIfAbsent(
                market.getSymbol(),
                k -> new MemoryOrderBook(market.getAmountScale())
        );

        List<MatchResult> results = book.match(taker);

        if (taker.getRemainingQuantity().compareTo(java.math.BigDecimal.ZERO) > 0) {
            book.add(taker);
        }

        return results;
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

    public void clearAll() {
        orderBooks.clear();
    }
}
