package com.coinflow.support;

import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookEntry;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.trade.domain.Trade;
import com.coinflow.trade.repository.TradeRepository;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class IntegrityAssertions {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MatchingEngine matchingEngine;

    public IntegrityAssertions(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            TradeRepository tradeRepository,
            WalletRepository walletRepository,
            WalletLedgerRepository walletLedgerRepository,
            MatchingEngine matchingEngine
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.walletRepository = walletRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.matchingEngine = matchingEngine;
    }

    public void assertAll() {
        assertWalletsNeverNegative();
        assertOrdersHaveValidQuantities();
        assertTradesHaveValidQuoteAmounts();
        assertOrderExecutedQuantityMatchesTrades();
        assertLedgersAreConsistentWithWallets();
        assertOrderBookMatchesOpenOrders();
    }

    private void assertWalletsNeverNegative() {
        for (Wallet wallet : walletRepository.findAll()) {
            assertThat(wallet.getAvailableBalance())
                    .as("wallet %s %s available balance", wallet.getUserId(), wallet.getAsset())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(wallet.getLockedBalance())
                    .as("wallet %s %s locked balance", wallet.getUserId(), wallet.getAsset())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    private void assertOrdersHaveValidQuantities() {
        for (Order order : orderRepository.findAll()) {
            assertThat(order.getOriginalQuantity())
                    .as("order %s original quantity", order.getId())
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(order.getExecutedQuantity())
                    .as("order %s executed quantity", order.getId())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(order.getRemainingQuantity())
                    .as("order %s remaining quantity", order.getId())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(order.getLockedAmount())
                    .as("order %s locked amount", order.getId())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(order.getExecutedQuantity().compareTo(order.getOriginalQuantity()))
                    .as("order %s executed <= original", order.getId())
                    .isLessThanOrEqualTo(0);
            assertThat(order.getExecutedQuantity().add(order.getRemainingQuantity()))
                    .as("order %s executed + remaining = original", order.getId())
                    .isEqualByComparingTo(order.getOriginalQuantity());

            if (order.getStatus() == OrderStatus.FILLED) {
                assertThat(order.getRemainingQuantity())
                        .as("filled order %s remaining quantity", order.getId())
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(order.getLockedAmount())
                        .as("filled order %s locked amount", order.getId())
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(order.getClosedAt())
                        .as("filled order %s closedAt", order.getId())
                        .isNotNull();
            }

            if (order.getStatus() == OrderStatus.CANCELED) {
                assertThat(order.getLockedAmount())
                        .as("canceled order %s locked amount", order.getId())
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(order.getClosedAt())
                        .as("canceled order %s closedAt", order.getId())
                        .isNotNull();
            }
        }
    }

    private void assertTradesHaveValidQuoteAmounts() {
        Map<Long, Market> markets = marketRepository.findAll().stream()
                .collect(Collectors.toMap(Market::getId, Function.identity()));
        Map<Long, Order> orders = orderRepository.findAll().stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));

        for (Trade trade : tradeRepository.findAll()) {
            Market market = markets.get(trade.getMarketId());
            assertThat(market)
                    .as("trade %s market", trade.getId())
                    .isNotNull();
            assertThat(orders).containsKey(trade.getBuyOrderId());
            assertThat(orders).containsKey(trade.getSellOrderId());
            assertThat(orders).containsKey(trade.getMakerOrderId());
            assertThat(orders).containsKey(trade.getTakerOrderId());
            assertThat(trade.getPrice())
                    .as("trade %s price", trade.getId())
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(trade.getQuantity())
                    .as("trade %s quantity", trade.getId())
                    .isGreaterThan(BigDecimal.ZERO);

            BigDecimal expectedQuoteAmount = trade.getPrice()
                    .multiply(trade.getQuantity())
                    .setScale(market.getAmountScale(), RoundingMode.DOWN);
            assertThat(trade.getQuoteAmount())
                    .as("trade %s quote amount", trade.getId())
                    .isEqualByComparingTo(expectedQuoteAmount);
        }
    }

    private void assertOrderExecutedQuantityMatchesTrades() {
        Map<Long, List<Trade>> tradesByOrderId = new HashMap<>();
        for (Trade trade : tradeRepository.findAll()) {
            tradesByOrderId.computeIfAbsent(trade.getBuyOrderId(), ignored -> new ArrayList<>()).add(trade);
            tradesByOrderId.computeIfAbsent(trade.getSellOrderId(), ignored -> new ArrayList<>()).add(trade);
        }

        for (Order order : orderRepository.findAll()) {
            List<Trade> trades = tradesByOrderId.getOrDefault(order.getId(), List.of());
            BigDecimal executedQuantity = trades.stream()
                    .map(Trade::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal executedQuoteAmount = trades.stream()
                    .map(Trade::getQuoteAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(order.getExecutedQuantity())
                    .as("order %s executed quantity matches trades", order.getId())
                    .isEqualByComparingTo(executedQuantity);
            assertThat(order.getExecutedQuoteAmount())
                    .as("order %s executed quote amount matches trades", order.getId())
                    .isEqualByComparingTo(executedQuoteAmount);
        }
    }

    private void assertLedgersAreConsistentWithWallets() {
        Map<Long, Wallet> wallets = walletRepository.findAll().stream()
                .collect(Collectors.toMap(Wallet::getId, Function.identity()));
        Map<Long, List<WalletLedger>> ledgersByWalletId = walletLedgerRepository.findAll().stream()
                .collect(Collectors.groupingBy(WalletLedger::getWalletId));

        for (Map.Entry<Long, List<WalletLedger>> entry : ledgersByWalletId.entrySet()) {
            Wallet wallet = wallets.get(entry.getKey());
            assertThat(wallet)
                    .as("ledger wallet %s exists", entry.getKey())
                    .isNotNull();

            List<WalletLedger> ledgers = entry.getValue().stream()
                    .sorted((left, right) -> left.getId().compareTo(right.getId()))
                    .toList();

            for (WalletLedger ledger : ledgers) {
                assertThat(ledger.getUserId()).isEqualTo(wallet.getUserId());
                assertThat(ledger.getAsset()).isEqualTo(wallet.getAsset());
                assertThat(ledger.getAvailableBalanceAfter())
                        .as("ledger %s available after", ledger.getId())
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(ledger.getLockedBalanceAfter())
                        .as("ledger %s locked after", ledger.getId())
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            }

            for (int i = 1; i < ledgers.size(); i++) {
                WalletLedger previous = ledgers.get(i - 1);
                WalletLedger current = ledgers.get(i);

                assertThat(previous.getAvailableBalanceAfter().add(current.getDeltaAvailable()))
                        .as("ledger %s available transition", current.getId())
                        .isEqualByComparingTo(current.getAvailableBalanceAfter());
                assertThat(previous.getLockedBalanceAfter().add(current.getDeltaLocked()))
                        .as("ledger %s locked transition", current.getId())
                        .isEqualByComparingTo(current.getLockedBalanceAfter());
            }

            WalletLedger latest = ledgers.get(ledgers.size() - 1);
            assertThat(latest.getAvailableBalanceAfter())
                    .as("wallet %s latest ledger available", wallet.getId())
                    .isEqualByComparingTo(wallet.getAvailableBalance());
            assertThat(latest.getLockedBalanceAfter())
                    .as("wallet %s latest ledger locked", wallet.getId())
                    .isEqualByComparingTo(wallet.getLockedBalance());
        }
    }

    private void assertOrderBookMatchesOpenOrders() {
        Map<Long, Order> orders = orderRepository.findAll().stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));

        for (Market market : marketRepository.findAll()) {
            assertOrderBookSide(market, OrderSide.BUY, matchingEngine.getBuySide(market.getSymbol()), orders);
            assertOrderBookSide(market, OrderSide.SELL, matchingEngine.getSellSide(market.getSymbol()), orders);
        }
    }

    private void assertOrderBookSide(
            Market market,
            OrderSide side,
            List<OrderBookEntry> entries,
            Map<Long, Order> orders
    ) {
        Set<Long> entryOrderIds = entries.stream()
                .map(OrderBookEntry::orderId)
                .collect(Collectors.toCollection(HashSet::new));

        for (OrderBookEntry entry : entries) {
            Order order = orders.get(entry.orderId());
            assertThat(order)
                    .as("orderbook entry %s order exists", entry.orderId())
                    .isNotNull();
            assertThat(order.getMarketId()).isEqualTo(market.getId());
            assertThat(order.getSide()).isEqualTo(side);
            assertThat(order.getStatus()).isIn(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);
            assertThat(entry.price()).isEqualByComparingTo(order.getPrice());
            assertThat(entry.remainingQuantity()).isEqualByComparingTo(order.getRemainingQuantity());
            assertThat(entry.sequence()).isEqualTo(order.getSequence());
        }

        Set<Long> expectedOrderIds = orders.values().stream()
                .filter(order -> order.getMarketId().equals(market.getId()))
                .filter(order -> order.getSide() == side)
                .filter(order -> order.getStatus() == OrderStatus.OPEN
                        || order.getStatus() == OrderStatus.PARTIALLY_FILLED)
                .map(Order::getId)
                .collect(Collectors.toSet());

        assertThat(entryOrderIds)
                .as("orderbook %s %s open order ids", market.getSymbol(), side)
                .containsExactlyInAnyOrderElementsOf(expectedOrderIds);
    }
}
