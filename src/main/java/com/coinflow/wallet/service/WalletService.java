package com.coinflow.wallet.service;

import com.coinflow.wallet.dto.WalletLedgerResponse;
import com.coinflow.wallet.dto.WalletResponse;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;

    @Transactional(readOnly = true)
    public List<WalletResponse> getWallets(Long userId) {
        return walletRepository.findAllByUserId(userId)
                .stream()
                .map(WalletResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WalletLedgerResponse> getLedgers(Long userId, String asset) {
        var ledgers = (asset != null)
                ? walletLedgerRepository.findAllByUserIdAndAssetOrderByCreatedAtDesc(userId, asset)
                : walletLedgerRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        return ledgers.stream().map(WalletLedgerResponse::from).toList();
    }
}
