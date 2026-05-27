package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.order.domain.Order;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerJdbcRepository;
import com.coinflow.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderAssetLockService {

    private final WalletRepository walletRepository;
    private final WalletLedgerJdbcRepository walletLedgerJdbcRepository;
    private final OrderCreateStageRecorder stageRecorder;

    public OrderAssetLockService(
            WalletRepository walletRepository,
            WalletLedgerJdbcRepository walletLedgerJdbcRepository,
            OrderCreateStageRecorder stageRecorder
    ) {
        this.walletRepository = walletRepository;
        this.walletLedgerJdbcRepository = walletLedgerJdbcRepository;
        this.stageRecorder = stageRecorder;
    }

    public Wallet lockTakerWallet(Long userId, CreateOrderCommand command) {
        Wallet wallet = stageRecorder.record(
                command.market().getSymbol(), command.side(), "taker_wallet_lock",
                () -> walletRepository.findByUserIdAndAssetWithLock(userId, command.lockedAsset())
                        .orElseThrow(() -> new ApiException(ErrorCode.INSUFFICIENT_BALANCE))
        );
        if (wallet.getAvailableBalance().compareTo(command.lockedAmount()) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        wallet.lock(command.lockedAmount());
        return wallet;
    }

    public void recordOrderLockLedger(Wallet wallet, Order order, CreateOrderCommand command) {
        stageRecorder.record(command.market().getSymbol(), command.side(), "order_lock_ledger_save", () -> {
            walletLedgerJdbcRepository.save(WalletLedger.create(
                    wallet,
                    LedgerType.ORDER_LOCK,
                    command.lockedAmount().negate(),
                    command.lockedAmount(),
                    order.getId(),
                    null
            ));
            return null;
        });
    }
}
