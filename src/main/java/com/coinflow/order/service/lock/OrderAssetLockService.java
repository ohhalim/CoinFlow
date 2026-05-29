package com.coinflow.order.service.lock;

import com.coinflow.order.domain.Order;
import com.coinflow.order.service.command.CreateOrderCommand;
import com.coinflow.order.service.metrics.OrderCreateStageRecorder;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.service.WalletOrderOperationService;
import org.springframework.stereotype.Service;

@Service
public class OrderAssetLockService {

    private final WalletOrderOperationService walletOrderOperationService;
    private final OrderCreateStageRecorder stageRecorder;

    public OrderAssetLockService(
            WalletOrderOperationService walletOrderOperationService,
            OrderCreateStageRecorder stageRecorder
    ) {
        this.walletOrderOperationService = walletOrderOperationService;
        this.stageRecorder = stageRecorder;
    }

    public Wallet lockTakerWallet(Long userId, CreateOrderCommand command) {
        return stageRecorder.record(
                command.market().getSymbol(), command.side(), "taker_wallet_lock",
                () -> walletOrderOperationService.lockOrderAsset(
                        userId,
                        command.lockedAsset(),
                        command.lockedAmount()
                )
        );
    }

    public WalletLedger createOrderLockLedger(Wallet wallet, Order order, CreateOrderCommand command) {
        return walletOrderOperationService.createOrderLockLedger(wallet, command.lockedAmount(), order.getId());
    }

    public void saveOrderLockLedger(WalletLedger ledger, CreateOrderCommand command) {
        stageRecorder.record(command.market().getSymbol(), command.side(), "order_lock_ledger_save", () -> {
            walletOrderOperationService.saveOrderLockLedger(ledger);
            return null;
        });
    }
}
