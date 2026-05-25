package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.matching.MatchResult;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.trade.domain.Trade;
import com.coinflow.trade.repository.TradeRepository;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class OrderSettlementService {

    private final OrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final TradeRepository tradeRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final DomainEventRecorder eventRecorder;
    private final OrderCreateStageRecorder stageRecorder;

    public OrderSettlementService(
            OrderRepository orderRepository,
            WalletRepository walletRepository,
            TradeRepository tradeRepository,
            WalletLedgerRepository walletLedgerRepository,
            DomainEventRecorder eventRecorder,
            OrderCreateStageRecorder stageRecorder
    ) {
        this.orderRepository = orderRepository;
        this.walletRepository = walletRepository;
        this.tradeRepository = tradeRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.eventRecorder = eventRecorder;
        this.stageRecorder = stageRecorder;
    }

    public List<Trade> settle(
            Market market,
            Order taker,
            List<MatchResult> matchResults,
            List<Order> autoCanceledMakers
    ) {
        if (matchResults.isEmpty()) return List.of();

        List<Trade> trades = new ArrayList<>();

        for (MatchResult result : matchResults) {
            Order maker = stageRecorder.record(
                    market.getSymbol(), taker.getSide(), "maker_order_lock",
                    () -> orderRepository.findByIdWithLock(result.makerOrderId()).orElseThrow()
            );
            if (!maker.isCancelable()) {
                throw new ApiException(ErrorCode.ORDER_NOT_FOUND);
            }

            boolean takerIsBuy = taker.getSide() == OrderSide.BUY;
            Order buyOrder = takerIsBuy ? taker : maker;

            SettlementAmounts amounts = stageRecorder.record(
                    market.getSymbol(), taker.getSide(), "settlement_order_fill",
                    () -> {
                        BigDecimal oldBuyLocked = buyOrder.getLockedAmount();

                        maker.fill(result.quantity(), result.quoteAmount(), market.getAmountScale());
                        taker.fill(result.quantity(), result.quoteAmount(), market.getAmountScale());

                        BigDecimal buyerReleased = oldBuyLocked.subtract(buyOrder.getLockedAmount());
                        BigDecimal buyerRefund = buyerReleased.subtract(result.quoteAmount());
                        return new SettlementAmounts(buyerReleased, buyerRefund);
                    }
            );

            WalletKey buyerBaseKey = new WalletKey(result.buyUserId(), market.getBaseAsset());
            WalletKey sellerQuoteKey = new WalletKey(result.sellUserId(), market.getQuoteAsset());
            WalletKey sellerBaseKey = new WalletKey(result.sellUserId(), market.getBaseAsset());
            WalletKey buyerQuoteKey = new WalletKey(result.buyUserId(), market.getQuoteAsset());
            Map<WalletKey, Wallet> wallets = lockWalletsInOrder(
                    market.getSymbol(),
                    taker.getSide(),
                    buyerBaseKey,
                    sellerQuoteKey,
                    sellerBaseKey,
                    buyerQuoteKey
            );

            Wallet buyerBaseWallet = wallets.get(buyerBaseKey);
            Wallet sellerQuoteWallet = wallets.get(sellerQuoteKey);
            Wallet sellerBaseWallet = wallets.get(sellerBaseKey);
            Wallet buyerQuoteWallet = wallets.get(buyerQuoteKey);

            stageRecorder.record(market.getSymbol(), taker.getSide(), "settlement_wallet_mutation", () -> {
                buyerQuoteWallet.consumeLocked(amounts.buyerReleased());
                if (amounts.buyerRefund().compareTo(BigDecimal.ZERO) > 0) {
                    buyerQuoteWallet.deposit(amounts.buyerRefund());
                }
                buyerBaseWallet.deposit(result.quantity());
                sellerBaseWallet.consumeLocked(result.quantity());
                sellerQuoteWallet.deposit(result.quoteAmount());
                return null;
            });

            Trade trade = Trade.create(
                    market.getId(), market.getSymbol(),
                    result.buyOrderId(), result.sellOrderId(),
                    result.makerOrderId(), result.takerOrderId(),
                    result.buyUserId(), result.sellUserId(),
                    result.price(), result.quantity(), result.quoteAmount()
            );
            stageRecorder.record(market.getSymbol(), taker.getSide(), "trade_save",
                    () -> tradeRepository.save(trade));

            Long buyOrderId = result.buyOrderId();
            Long sellOrderId = result.sellOrderId();
            Long tradeId = trade.getId();

            stageRecorder.record(market.getSymbol(), taker.getSide(), "settlement_trade_event_save", () -> {
                eventRecorder.recordOrderFillEvent(maker, tradeId);
                eventRecorder.recordOrderFillEvent(taker, tradeId);
                eventRecorder.recordTradeCreated(trade);
                return null;
            });

            stageRecorder.record(market.getSymbol(), taker.getSide(), "settlement_ledger_save", () -> {
                walletLedgerRepository.save(WalletLedger.create(
                        buyerQuoteWallet, LedgerType.TRADE_BUY_QUOTE_SETTLE,
                        amounts.buyerRefund(), amounts.buyerReleased().negate(),
                        buyOrderId, tradeId
                ));
                walletLedgerRepository.save(WalletLedger.create(
                        buyerBaseWallet, LedgerType.TRADE_BUY_BASE_CREDIT,
                        result.quantity(), BigDecimal.ZERO,
                        buyOrderId, tradeId
                ));
                walletLedgerRepository.save(WalletLedger.create(
                        sellerBaseWallet, LedgerType.TRADE_SELL_BASE_SETTLE,
                        BigDecimal.ZERO, result.quantity().negate(),
                        sellOrderId, tradeId
                ));
                walletLedgerRepository.save(WalletLedger.create(
                        sellerQuoteWallet, LedgerType.TRADE_SELL_QUOTE_CREDIT,
                        result.quoteAmount(), BigDecimal.ZERO,
                        sellOrderId, tradeId
                ));
                return null;
            });

            if (maker.getRemainingQuantity().signum() > 0) {
                stageRecorder.record(market.getSymbol(), taker.getSide(), "settlement_dust_cancel", () -> {
                    BigDecimal dustCheck = maker.getPrice()
                            .multiply(maker.getRemainingQuantity())
                            .setScale(market.getAmountScale(), RoundingMode.DOWN);
                    if (dustCheck.signum() == 0) {
                        Wallet makerLockedWallet = takerIsBuy ? sellerBaseWallet : buyerQuoteWallet;
                        BigDecimal dustRelease = maker.releasableAmount();
                        makerLockedWallet.unlock(dustRelease);
                        maker.cancel();
                        autoCanceledMakers.add(maker);
                        walletLedgerRepository.save(WalletLedger.create(
                                makerLockedWallet, LedgerType.ORDER_CANCEL_RELEASE,
                                dustRelease, dustRelease.negate(),
                                maker.getId(), tradeId
                        ));
                        eventRecorder.recordOrderCanceled(maker, maker.getLockedAsset(), dustRelease.toPlainString());
                    }
                    return null;
                });
            }

            stageRecorder.record(market.getSymbol(), taker.getSide(), "settlement_completed_event_save", () -> {
                eventRecorder.recordSettlementCompleted(trade);
                return null;
            });
            trades.add(trade);
        }

        return trades;
    }

    private Map<WalletKey, Wallet> lockWalletsInOrder(String marketSymbol, OrderSide side, WalletKey... keys) {
        List<WalletKey> sortedKeys = Stream.of(keys)
                .distinct()
                .sorted()
                .toList();

        Map<WalletKey, Wallet> wallets = new HashMap<>();
        for (WalletKey key : sortedKeys) {
            Wallet wallet = stageRecorder.record(
                    marketSymbol, side, "settlement_wallet_lock",
                    () -> walletRepository.findByUserIdAndAssetWithLock(key.userId(), key.asset())
                            .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND))
            );
            wallets.put(key, wallet);
        }
        return wallets;
    }

    private record WalletKey(Long userId, String asset) implements Comparable<WalletKey> {
        @Override
        public int compareTo(WalletKey other) {
            int userCompare = this.userId.compareTo(other.userId);
            if (userCompare != 0) return userCompare;
            return this.asset.compareTo(other.asset);
        }
    }

    private record SettlementAmounts(BigDecimal buyerReleased, BigDecimal buyerRefund) {
    }
}
