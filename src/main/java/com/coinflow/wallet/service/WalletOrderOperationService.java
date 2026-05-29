package com.coinflow.wallet.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerJdbcRepository;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class WalletOrderOperationService {

    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final WalletLedgerJdbcRepository walletLedgerJdbcRepository;

    public WalletOrderOperationService(
            WalletRepository walletRepository,
            WalletLedgerRepository walletLedgerRepository,
            WalletLedgerJdbcRepository walletLedgerJdbcRepository
    ) {
        this.walletRepository = walletRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.walletLedgerJdbcRepository = walletLedgerJdbcRepository;
    }

    public Wallet lockOrderAsset(Long userId, String asset, BigDecimal amount) {
        Wallet wallet = lockWallet(userId, asset, ErrorCode.INSUFFICIENT_BALANCE);
        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        wallet.lock(amount);
        return wallet;
    }

    public WalletLedger createOrderLockLedger(Wallet wallet, BigDecimal amount, Long orderId) {
        return WalletLedger.create(
                wallet,
                LedgerType.ORDER_LOCK,
                amount.negate(),
                amount,
                orderId,
                null
        );
    }

    public void saveOrderLockLedger(WalletLedger ledger) {
        walletLedgerJdbcRepository.save(ledger);
    }

    public WalletLedger releaseOrderLock(
            Long userId,
            String asset,
            BigDecimal amount,
            Long orderId,
            LedgerType ledgerType
    ) {
        return releaseOrderLock(userId, asset, amount, orderId, ledgerType, ErrorCode.WALLET_NOT_FOUND);
    }

    public WalletLedger releaseOrderLock(
            Long userId,
            String asset,
            BigDecimal amount,
            Long orderId,
            LedgerType ledgerType,
            ErrorCode notFoundErrorCode
    ) {
        Wallet wallet = lockWallet(userId, asset, notFoundErrorCode);
        wallet.unlock(amount);
        WalletLedger ledger = WalletLedger.create(
                wallet,
                ledgerType,
                amount,
                amount.negate(),
                orderId,
                null
        );
        walletLedgerRepository.save(ledger);
        return ledger;
    }

    public Wallet lockWallet(Long userId, String asset) {
        return lockWallet(userId, asset, ErrorCode.WALLET_NOT_FOUND);
    }

    public void saveLedgers(List<WalletLedger> ledgers) {
        walletLedgerJdbcRepository.saveAll(ledgers);
    }

    private Wallet lockWallet(Long userId, String asset, ErrorCode notFoundErrorCode) {
        return walletRepository.findByUserIdAndAssetWithLock(userId, asset)
                .orElseThrow(() -> new ApiException(notFoundErrorCode));
    }
}
