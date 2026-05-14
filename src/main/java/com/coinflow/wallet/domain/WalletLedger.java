package com.coinflow.wallet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "wallet_ledgers")
public class WalletLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long walletId;
    private String asset;

    @Enumerated(EnumType.STRING)
    private LedgerType type;

    private BigDecimal deltaAvailable;
    private BigDecimal deltaLocked;
    private BigDecimal availableBalanceAfter;
    private BigDecimal lockedBalanceAfter;

    private Long orderId;
    private Long tradeId;

    private LocalDateTime createdAt;

    public static WalletLedger create(
            Wallet wallet,
            LedgerType type,
            BigDecimal deltaAvailable,
            BigDecimal deltaLocked,
            Long orderId,
            Long tradeId
    ) {
        WalletLedger ledger = new WalletLedger();
        ledger.userId = wallet.getUserId();
        ledger.walletId = wallet.getId();
        ledger.asset = wallet.getAsset();
        ledger.type = type;
        ledger.deltaAvailable = deltaAvailable;
        ledger.deltaLocked = deltaLocked;
        ledger.availableBalanceAfter = wallet.getAvailableBalance();
        ledger.lockedBalanceAfter = wallet.getLockedBalance();
        ledger.orderId = orderId;
        ledger.tradeId = tradeId;
        ledger.createdAt = LocalDateTime.now();
        return ledger;
    }
}
