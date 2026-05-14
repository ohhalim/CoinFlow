package com.coinflow.order.matching;

import com.coinflow.market.domain.Market;
import com.coinflow.market.domain.MarketStatus;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookInitializer implements ApplicationRunner {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final MatchingEngine matchingEngine;

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        List<Market> markets = marketRepository.findAllByStatus(MarketStatus.ACTIVE);
        Map<Long, Market> marketMap = markets.stream()
                .collect(Collectors.toMap(Market::getId, m -> m));

        List<Order> openOrders = orderRepository.findAllByStatusInOrderBySequenceAsc(
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED)
        );

        for (Order order : openOrders) {
            Market market = marketMap.get(order.getMarketId());
            if (market != null) {
                matchingEngine.addToBook(market, order);
            }
        }

        log.info("OrderBook initialized: {} orders loaded across {} markets", openOrders.size(), markets.size());
    }
}
