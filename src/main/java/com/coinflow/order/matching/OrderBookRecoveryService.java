package com.coinflow.order.matching;

import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Service
public class OrderBookRecoveryService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final MatchingEngine matchingEngine;
    private final MeterRegistry meterRegistry;
    private final PlatformTransactionManager transactionManager;

    public OrderBookRecoveryService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            MatchingEngine matchingEngine,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.matchingEngine = matchingEngine;
        this.meterRegistry = meterRegistry;
        this.transactionManager = transactionManager;
    }

    public void rebuildAfterApplyFailure(Long marketId) {
        meterRegistry.counter("orderbook.apply.failure", "marketId", marketId.toString()).increment();

        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> rebuildMarketOrderBook(marketId));
        } catch (Exception rebuildFailure) {
            log.error("오더북 재빌드 실패: marketId={}, cancelOnly 전환 시도", marketId, rebuildFailure);
            enableCancelOnly(marketId);
        }
    }

    private void rebuildMarketOrderBook(Long marketId) {
        Market market = marketRepository.findById(marketId).orElseThrow();
        List<Order> openOrders = orderRepository.findAllByMarketIdAndStatusInOrderBySequenceAsc(
                marketId,
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED)
        );
        matchingEngine.rebuildBook(market, openOrders);
        log.info("OrderBook rebuilt after apply failure: market={}, openOrders={}", market.getSymbol(), openOrders.size());
    }

    private void enableCancelOnly(Long marketId) {
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> {
                Market market = marketRepository.findById(marketId).orElseThrow();
                market.enableCancelOnly();
                log.error("Market switched to cancelOnly after orderbook recovery failure: market={}", market.getSymbol());
            });
        } catch (Exception cancelOnlyFailure) {
            log.error("cancelOnly 전환 실패: marketId={}", marketId, cancelOnlyFailure);
        }
    }
}
