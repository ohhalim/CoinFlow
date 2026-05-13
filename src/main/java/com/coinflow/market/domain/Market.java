package com.coinflow.market.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "markets")
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String displayName;
    private String baseAsset;
    private String quoteAsset;

    private int amountScale;

    private BigDecimal tickSize;
    private BigDecimal stepSize;
    private BigDecimal minOrderQuantity;
    private BigDecimal minOrderAmount;

    @Enumerated(EnumType.STRING)
    private MarketStatus status;

    private boolean cancelOnly;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return status == MarketStatus.ACTIVE;
    }
}
