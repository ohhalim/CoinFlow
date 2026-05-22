package com.coinflow.order.service;

import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderType;
import com.coinflow.order.domain.TimeInForce;

import java.math.BigDecimal;

public record CreateOrderCommand(
        Market market,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        BigDecimal price,
        BigDecimal quantity,
        String lockedAsset,
        BigDecimal lockedAmount
) {
}
