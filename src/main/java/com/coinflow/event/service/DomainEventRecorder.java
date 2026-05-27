package com.coinflow.event.service;

import com.coinflow.event.domain.DomainEventType;
import com.coinflow.order.domain.Order;
import com.coinflow.trade.domain.Trade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DomainEventRecorder {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_SQL = """
            INSERT INTO domain_events (
                event_type,
                aggregate_type,
                aggregate_id,
                market_id,
                market_symbol,
                payload
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    public void recordOrderAccepted(Order order) {
        insert(DomainEventType.ORDER_ACCEPTED, "ORDER", order.getId(),
                order.getMarketId(), order.getMarketSymbol(),
                orderAcceptedPayload(order));
    }

    public void recordOrderFillEvent(Order order, Long tradeId) {
        DomainEventType type = (order.getRemainingQuantity().signum() == 0)
                ? DomainEventType.ORDER_FILLED
                : DomainEventType.ORDER_PARTIALLY_FILLED;

        insert(type, "ORDER", order.getId(),
                order.getMarketId(), order.getMarketSymbol(),
                Map.of(
                        "orderId", order.getId(),
                        "tradeId", tradeId,
                        "executedQuantity", order.getExecutedQuantity(),
                        "remainingQuantity", order.getRemainingQuantity(),
                        "executedQuoteAmount", order.getExecutedQuoteAmount()
                ));
    }

    public void recordOrderCanceled(Order order, String releasedAsset, String releasedAmount) {
        insert(DomainEventType.ORDER_CANCELED, "ORDER", order.getId(),
                order.getMarketId(), order.getMarketSymbol(),
                Map.of(
                        "orderId", order.getId(),
                        "userId", order.getUserId(),
                        "releasedAsset", releasedAsset,
                        "releasedAmount", releasedAmount
                ));
    }

    public void recordTradeCreated(Trade trade) {
        insert(DomainEventType.TRADE_CREATED, "TRADE", trade.getId(),
                trade.getMarketId(), trade.getMarketSymbol(),
                Map.of(
                        "tradeId", trade.getId(),
                        "market", trade.getMarketSymbol(),
                        "buyOrderId", trade.getBuyOrderId(),
                        "sellOrderId", trade.getSellOrderId(),
                        "makerOrderId", trade.getMakerOrderId(),
                        "takerOrderId", trade.getTakerOrderId(),
                        "price", trade.getPrice(),
                        "quantity", trade.getQuantity(),
                        "quoteAmount", trade.getQuoteAmount()
                ));
    }

    public void recordSettlementCompleted(Trade trade) {
        insert(DomainEventType.SETTLEMENT_COMPLETED, "TRADE", trade.getId(),
                trade.getMarketId(), trade.getMarketSymbol(),
                Map.of(
                        "tradeId", trade.getId(),
                        "buyOrderId", trade.getBuyOrderId(),
                        "sellOrderId", trade.getSellOrderId(),
                        "price", trade.getPrice(),
                        "quantity", trade.getQuantity(),
                        "quoteAmount", trade.getQuoteAmount()
                ));
    }

    public void recordSettlementEvents(Order maker, Order taker, Trade trade) {
        DomainEventType makerFillType = fillEventType(maker);
        DomainEventType takerFillType = fillEventType(taker);

        insertAll(List.of(
                createEvent(makerFillType, "ORDER", maker.getId(),
                        maker.getMarketId(), maker.getMarketSymbol(),
                        orderFillPayload(maker, trade.getId())),
                createEvent(takerFillType, "ORDER", taker.getId(),
                        taker.getMarketId(), taker.getMarketSymbol(),
                        orderFillPayload(taker, trade.getId())),
                createEvent(DomainEventType.TRADE_CREATED, "TRADE", trade.getId(),
                        trade.getMarketId(), trade.getMarketSymbol(),
                        tradeCreatedPayload(trade)),
                createEvent(DomainEventType.SETTLEMENT_COMPLETED, "TRADE", trade.getId(),
                        trade.getMarketId(), trade.getMarketSymbol(),
                        settlementCompletedPayload(trade))
        ));
    }

    public void recordOrderAcceptedAndSettlementEvents(Order acceptedOrder, Order maker, Order taker, Trade trade) {
        DomainEventType makerFillType = fillEventType(maker);
        DomainEventType takerFillType = fillEventType(taker);

        insertAll(List.of(
                createEvent(DomainEventType.ORDER_ACCEPTED, "ORDER", acceptedOrder.getId(),
                        acceptedOrder.getMarketId(), acceptedOrder.getMarketSymbol(),
                        orderAcceptedPayload(acceptedOrder)),
                createEvent(makerFillType, "ORDER", maker.getId(),
                        maker.getMarketId(), maker.getMarketSymbol(),
                        orderFillPayload(maker, trade.getId())),
                createEvent(takerFillType, "ORDER", taker.getId(),
                        taker.getMarketId(), taker.getMarketSymbol(),
                        orderFillPayload(taker, trade.getId())),
                createEvent(DomainEventType.TRADE_CREATED, "TRADE", trade.getId(),
                        trade.getMarketId(), trade.getMarketSymbol(),
                        tradeCreatedPayload(trade)),
                createEvent(DomainEventType.SETTLEMENT_COMPLETED, "TRADE", trade.getId(),
                        trade.getMarketId(), trade.getMarketSymbol(),
                        settlementCompletedPayload(trade))
        ));
    }

    private DomainEventType fillEventType(Order order) {
        return order.getRemainingQuantity().signum() == 0
                ? DomainEventType.ORDER_FILLED
                : DomainEventType.ORDER_PARTIALLY_FILLED;
    }

    private Map<String, Object> orderAcceptedPayload(Order order) {
        return Map.of(
                "orderId", order.getId(),
                "userId", order.getUserId(),
                "market", order.getMarketSymbol(),
                "side", order.getSide().name(),
                "price", order.getPrice(),
                "quantity", order.getOriginalQuantity()
        );
    }

    private Map<String, Object> orderFillPayload(Order order, Long tradeId) {
        return Map.of(
                "orderId", order.getId(),
                "tradeId", tradeId,
                "executedQuantity", order.getExecutedQuantity(),
                "remainingQuantity", order.getRemainingQuantity(),
                "executedQuoteAmount", order.getExecutedQuoteAmount()
        );
    }

    private Map<String, Object> tradeCreatedPayload(Trade trade) {
        return Map.of(
                "tradeId", trade.getId(),
                "market", trade.getMarketSymbol(),
                "buyOrderId", trade.getBuyOrderId(),
                "sellOrderId", trade.getSellOrderId(),
                "makerOrderId", trade.getMakerOrderId(),
                "takerOrderId", trade.getTakerOrderId(),
                "price", trade.getPrice(),
                "quantity", trade.getQuantity(),
                "quoteAmount", trade.getQuoteAmount()
        );
    }

    private Map<String, Object> settlementCompletedPayload(Trade trade) {
        return Map.of(
                "tradeId", trade.getId(),
                "buyOrderId", trade.getBuyOrderId(),
                "sellOrderId", trade.getSellOrderId(),
                "price", trade.getPrice(),
                "quantity", trade.getQuantity(),
                "quoteAmount", trade.getQuoteAmount()
        );
    }

    private void insert(DomainEventType type, String aggregateType, Long aggregateId,
                        Long marketId, String marketSymbol, Map<String, Object> payload) {
        insertAll(List.of(createEvent(type, aggregateType, aggregateId, marketId, marketSymbol, payload)));
    }

    private PendingDomainEvent createEvent(DomainEventType type, String aggregateType, Long aggregateId,
                                           Long marketId, String marketSymbol, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = Map.of(
                    "schemaVersion", "1.0",
                    "occurredAt", Instant.now().toString(),
                    "payload", payload
            );
            String json = objectMapper.writeValueAsString(envelope);
            return new PendingDomainEvent(type, aggregateType, aggregateId, marketId, marketSymbol, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize domain event payload: " + type, e);
        }
    }

    private void insertAll(List<PendingDomainEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                PendingDomainEvent event = events.get(index);
                ps.setString(1, event.type().name());
                ps.setString(2, event.aggregateType());
                ps.setLong(3, event.aggregateId());
                ps.setLong(4, event.marketId());
                ps.setString(5, event.marketSymbol());
                ps.setString(6, event.payload());
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
    }

    private record PendingDomainEvent(
            DomainEventType type,
            String aggregateType,
            Long aggregateId,
            Long marketId,
            String marketSymbol,
            String payload
    ) {
    }
}
