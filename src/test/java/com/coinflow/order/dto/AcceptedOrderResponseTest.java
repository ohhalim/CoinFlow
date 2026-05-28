package com.coinflow.order.dto;

import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderType;
import com.coinflow.order.domain.TimeInForce;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptedOrderResponseTest {

    @Test
    void 주문_접수_응답은_체결_결과를_포함하지_않는다() {
        Order order = Order.create(
                1L,
                10L,
                "BTC-KRW",
                OrderSide.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                new BigDecimal("100000000"),
                new BigDecimal("0.0001"),
                "KRW",
                new BigDecimal("10000"),
                100L,
                "client-order-1"
        );
        LocalDateTime acceptedAt = LocalDateTime.parse("2026-05-28T12:00:00");
        ReflectionTestUtils.setField(order, "id", 99L);
        ReflectionTestUtils.setField(order, "createdAt", acceptedAt);

        AcceptedOrderResponse response = AcceptedOrderResponse.of(order);

        assertThat(response.orderId()).isEqualTo(99L);
        assertThat(response.clientOrderId()).isEqualTo("client-order-1");
        assertThat(response.market()).isEqualTo("BTC-KRW");
        assertThat(response.side()).isEqualTo("BUY");
        assertThat(response.status()).isEqualTo("OPEN");
        assertThat(response.acceptedAt()).isEqualTo(acceptedAt);
    }
}
