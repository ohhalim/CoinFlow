package com.coinflow.wallet.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.dto.DepositRequest;
import com.coinflow.wallet.dto.WalletLedgerResponse;
import com.coinflow.wallet.dto.WalletResponse;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    @Transactional
    public WalletResponse deposit(Long userId, DepositRequest request) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(request.amount());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new ApiException(ErrorCode.INVALID_AMOUNT);

        var wallet = walletRepository.findByUserIdAndAssetWithLock(userId, request.asset())
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND));

        wallet.deposit(amount);

        walletLedgerRepository.save(WalletLedger.create(
                wallet, LedgerType.SEED_DEPOSIT,
                amount, BigDecimal.ZERO,
                null, null
        ));

        return WalletResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public List<WalletLedgerResponse> getLedgers(Long userId, String asset, int limit) {
        var pageable = PageRequest.of(0, limit);
        var ledgers = (asset != null)
                ? walletLedgerRepository.findAllByUserIdAndAssetOrderByCreatedAtDesc(userId, asset, pageable)
                : walletLedgerRepository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable);
        return ledgers.stream().map(WalletLedgerResponse::from).toList();
    }
}
