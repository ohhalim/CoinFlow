package com.coinflow.wallet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String asset;

    private BigDecimal availableBalance;
    private BigDecimal lockedBalance;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static Wallet create(Long userId, String asset) {
        Wallet wallet = new Wallet();
        wallet.userId = userId;
        wallet.asset = asset;
        wallet.availableBalance = BigDecimal.ZERO;
        wallet.lockedBalance = BigDecimal.ZERO;
        return wallet;
    }

    public void lock(BigDecimal amount) {
        this.availableBalance = this.availableBalance.subtract(amount);
        this.lockedBalance = this.lockedBalance.add(amount);
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
