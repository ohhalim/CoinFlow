package com.coinflow.order.matching;

import java.math.BigDecimal;

public record MatchResult(
        Long makerOrderId,
        Long takerOrderId,
        Long buyOrderId,
        Long sellOrderId,
        Long makerUserId,
        Long takerUserId,
        Long buyUserId,
        Long sellUserId,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteAmount
) {}
