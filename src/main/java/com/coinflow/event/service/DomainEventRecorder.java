package com.coinflow.event.service;

import com.coinflow.event.domain.DomainEvent;
import com.coinflow.event.domain.DomainEventType;
import com.coinflow.event.repository.DomainEventRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.trade.domain.Trade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class DomainEventRecorder {

    private final DomainEventRepository domainEventRepository;
    private final ObjectMapper objectMapper;

    public void recordOrderAccepted(Order order) {
        save(DomainEventType.ORDER_ACCEPTED, "ORDER", order.getId(),
                order.getMarketId(), order.getMarketSymbol(),
                Map.of(
                        "orderId", order.getId(),
                        "userId", order.getUserId(),
                        "market", order.getMarketSymbol(),
                        "side", order.getSide().name(),
                        "price", order.getPrice(),
                        "quantity", order.getOriginalQuantity()
                ));
    }

    public void recordOrderFillEvent(Order order, Long tradeId) {
        DomainEventType type = (order.getRemainingQuantity().signum() == 0)
                ? DomainEventType.ORDER_FILLED
                : DomainEventType.ORDER_PARTIALLY_FILLED;

        save(type, "ORDER", order.getId(),
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
        save(DomainEventType.ORDER_CANCELED, "ORDER", order.getId(),
                order.getMarketId(), order.getMarketSymbol(),
                Map.of(
                        "orderId", order.getId(),
                        "userId", order.getUserId(),
                        "releasedAsset", releasedAsset,
                        "releasedAmount", releasedAmount
                ));
    }

    public void recordTradeCreated(Trade trade) {
        save(DomainEventType.TRADE_CREATED, "TRADE", trade.getId(),
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
        save(DomainEventType.SETTLEMENT_COMPLETED, "TRADE", trade.getId(),
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

    private void save(DomainEventType type, String aggregateType, Long aggregateId,
                      Long marketId, String marketSymbol, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            domainEventRepository.save(
                    DomainEvent.create(type, aggregateType, aggregateId, marketId, marketSymbol, json)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize domain event payload: " + type, e);
        }
    }
}
