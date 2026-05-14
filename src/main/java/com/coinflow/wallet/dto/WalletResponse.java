package com.coinflow.wallet.dto;

import com.coinflow.wallet.domain.Wallet;

public record WalletResponse(
        Long walletId,
        String asset,
        String availableBalance,
        String lockedBalance
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getAsset(),
                wallet.getAvailableBalance().toPlainString(),
                wallet.getLockedBalance().toPlainString()
        );
    }
}
