package com.coinflow.wallet.dto;

import com.coinflow.wallet.domain.WalletLedger;

import java.time.LocalDateTime;

public record WalletLedgerResponse(
        Long ledgerId,
        String asset,
        String type,
        String deltaAvailable,
        String deltaLocked,
        String availableBalanceAfter,
        String lockedBalanceAfter,
        Long orderId,
        Long tradeId,
        LocalDateTime createdAt
) {
    public static WalletLedgerResponse from(WalletLedger ledger) {
        return new WalletLedgerResponse(
                ledger.getId(),
                ledger.getAsset(),
                ledger.getType().name(),
                ledger.getDeltaAvailable().toPlainString(),
                ledger.getDeltaLocked().toPlainString(),
                ledger.getAvailableBalanceAfter().toPlainString(),
                ledger.getLockedBalanceAfter().toPlainString(),
                ledger.getOrderId(),
                ledger.getTradeId(),
                ledger.getCreatedAt()
        );
    }
}
