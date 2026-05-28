package com.coinflow.order.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor
@Table(
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_orders_user_client_order",
                        columnNames = {"user_id", "client_order_id"}
                )
        }
)
public class    Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String clientOrderId;

    private Long userId;
    private Long marketId;
    private String marketSymbol;

    @Enumerated(EnumType.STRING)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    private OrderType type;

    @Enumerated(EnumType.STRING)
    private TimeInForce timeInForce;

    private BigDecimal price;
    private BigDecimal originalQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal executedQuantity;
    private BigDecimal executedQuoteAmount;

    private String lockedAsset;
    private BigDecimal lockedAmount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Long sequence;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;

    public static Order create(
            Long userId,
            Long marketId,
            String marketSymbol,
            OrderSide side,
            OrderType type,
            TimeInForce timeInForce,
            BigDecimal price,
            BigDecimal originalQuantity,
            String lockedAsset,
            BigDecimal lockedAmount,
            Long sequence,
            String clientOrderId
    ) {
        Order order = new Order();
        order.userId = userId;
        order.marketId = marketId;
        order.marketSymbol = marketSymbol;
        order.side = side;
        order.type = type;
        order.timeInForce = timeInForce;
        order.price = price;
        order.originalQuantity = originalQuantity;
        order.remainingQuantity = originalQuantity;
        order.executedQuantity = BigDecimal.ZERO;
        order.executedQuoteAmount = BigDecimal.ZERO;
        order.lockedAsset = lockedAsset;
        order.lockedAmount = lockedAmount;
        order.status = OrderStatus.OPEN;
        order.sequence = sequence;
        order.clientOrderId = clientOrderId;
        return order;
    }

    public static Order accepted(
            Long userId,
            Long marketId,
            String marketSymbol,
            OrderSide side,
            OrderType type,
            TimeInForce timeInForce,
            BigDecimal price,
            BigDecimal originalQuantity,
            String lockedAsset,
            BigDecimal lockedAmount,
            Long sequence,
            String clientOrderId
    ) {
        Order order = create(
                userId,
                marketId,
                marketSymbol,
                side,
                type,
                timeInForce,
                price,
                originalQuantity,
                lockedAsset,
                lockedAmount,
                sequence,
                clientOrderId
        );
        order.status = OrderStatus.ACCEPTED;
        return order;
    }

    public boolean isCancelable() {
        return status == OrderStatus.ACCEPTED
                || status == OrderStatus.OPEN
                || status == OrderStatus.PARTIALLY_FILLED;
    }

    public BigDecimal releasableAmount() {
        return lockedAmount;
    }

    public void fill(BigDecimal quantity, BigDecimal quoteAmount, int amountScale) {
        this.executedQuantity = this.executedQuantity.add(quantity);
        this.executedQuoteAmount = this.executedQuoteAmount.add(quoteAmount);
        this.remainingQuantity = this.remainingQuantity.subtract(quantity);

        if (this.side == OrderSide.BUY) {
            this.lockedAmount = (this.remainingQuantity.compareTo(BigDecimal.ZERO) == 0)
                    ? BigDecimal.ZERO
                    : this.price.multiply(this.remainingQuantity).setScale(amountScale, RoundingMode.CEILING);
        } else {
            this.lockedAmount = this.lockedAmount.subtract(quantity);
        }

        if (this.remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
            this.status = OrderStatus.FILLED;
            this.closedAt = LocalDateTime.now();
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    public void cancel() {
        this.status = OrderStatus.CANCELED;
        this.lockedAmount = BigDecimal.ZERO;
        this.closedAt = LocalDateTime.now();
    }

    public void open() {
        if (this.status == OrderStatus.ACCEPTED) {
            this.status = OrderStatus.OPEN;
        }
    }

    public void reject() {
        this.status = OrderStatus.REJECTED;
        this.lockedAmount = BigDecimal.ZERO;
        this.closedAt = LocalDateTime.now();
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
