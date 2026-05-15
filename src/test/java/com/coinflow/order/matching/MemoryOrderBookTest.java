package com.coinflow.order.matching;

import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderType;
import com.coinflow.order.domain.TimeInForce;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryOrderBookTest {

    @Test
    void planMatch_zero_quote_체결은_생성하지_않고_매칭을_중단한다() {
        MemoryOrderBook orderBook = new MemoryOrderBook(0);
        Order maker = order(
                1L,
                10L,
                OrderSide.SELL,
                "9999",
                "0.0001",
                "BTC",
                "0.0001",
                1L
        );
        Order taker = order(
                2L,
                20L,
                OrderSide.BUY,
                "9999",
                "10",
                "KRW",
                "99990",
                2L
        );

        orderBook.add(maker);

        List<MatchResult> plan = orderBook.planMatch(taker);

        assertThat(plan).isEmpty();
        assertThat(orderBook.getSellSide())
                .extracting(OrderBookEntry::orderId)
                .containsExactly(10L);
    }

    private Order order(
            Long userId,
            Long orderId,
            OrderSide side,
            String price,
            String quantity,
            String lockedAsset,
            String lockedAmount,
            Long sequence
    ) {
        Order order = Order.create(
                userId,
                1L,
                "BTC-KRW",
                side,
                OrderType.LIMIT,
                TimeInForce.GTC,
                new BigDecimal(price),
                new BigDecimal(quantity),
                lockedAsset,
                new BigDecimal(lockedAmount),
                sequence,
                null
        );
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }
}
