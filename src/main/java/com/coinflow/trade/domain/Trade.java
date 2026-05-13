package com.coinflow.trade.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long marketId;
    private String marketSymbol;

    private Long buyOrderId;
    private Long sellOrderId;
    private Long makerOrderId;
    private Long takerOrderId;

    private Long buyUserId;
    private Long sellUserId;

    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal quoteAmount;

    private LocalDateTime tradedAt;

    public static Trade create(
            Long marketId,
            String marketSymbol,
            Long buyOrderId,
            Long sellOrderId,
            Long makerOrderId,
            Long takerOrderId,
            Long buyUserId,
            Long sellUserId,
            BigDecimal price,
            BigDecimal quantity,
            BigDecimal quoteAmount
    ) {
        Trade trade = new Trade();
        trade.marketId = marketId;
        trade.marketSymbol = marketSymbol;
        trade.buyOrderId = buyOrderId;
        trade.sellOrderId = sellOrderId;
        trade.makerOrderId = makerOrderId;
        trade.takerOrderId = takerOrderId;
        trade.buyUserId = buyUserId;
        trade.sellUserId = sellUserId;
        trade.price = price;
        trade.quantity = quantity;
        trade.quoteAmount = quoteAmount;
        trade.tradedAt = LocalDateTime.now();
        return trade;
    }
}
