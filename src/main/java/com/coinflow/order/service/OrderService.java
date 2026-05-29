package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.dto.AcceptedOrderResponse;
import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.CreateOrderResponse;
import com.coinflow.order.dto.OrderDetailResponse;
import com.coinflow.order.dto.OrderSummaryResponse;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.service.cancel.OrderCancelService;
import com.coinflow.order.service.command.CreateOrderCommand;
import com.coinflow.order.service.command.MarketOrderCommandQueue;
import com.coinflow.order.service.processor.SyncOrderProcessor;
import com.coinflow.order.service.support.ClientOrderIdService;
import com.coinflow.order.service.support.OrderCreateValidator;
import com.coinflow.common.pagination.OffsetBasedPageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final OrderCreateValidator orderCreateValidator;
    private final ClientOrderIdService clientOrderIdService;
    private final MarketOrderCommandQueue marketOrderCommandQueue;
    private final SyncOrderProcessor syncOrderProcessor;
    private final AcceptedOrderService acceptedOrderService;
    private final OrderCancelService orderCancelService;

    public OrderService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            OrderCreateValidator orderCreateValidator,
            ClientOrderIdService clientOrderIdService,
            MarketOrderCommandQueue marketOrderCommandQueue,
            SyncOrderProcessor syncOrderProcessor,
            AcceptedOrderService acceptedOrderService,
            OrderCancelService orderCancelService
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.orderCreateValidator = orderCreateValidator;
        this.clientOrderIdService = clientOrderIdService;
        this.marketOrderCommandQueue = marketOrderCommandQueue;
        this.syncOrderProcessor = syncOrderProcessor;
        this.acceptedOrderService = acceptedOrderService;
        this.orderCancelService = orderCancelService;
    }

    public CreateOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {
        long createStartedAt = System.nanoTime();

        Market market = marketRepository.findBySymbol(request.market())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        CreateOrderCommand command = orderCreateValidator.validate(market, request);
        OrderSide side = command.side();
        clientOrderIdService.validateUnique(currentUserId, request, market, side);

        return marketOrderCommandQueue.submit(
                market,
                side,
                () -> syncOrderProcessor.process(currentUserId, request, market, command, createStartedAt)
        );
    }

    public AcceptedOrderResponse acceptOrder(Long currentUserId, CreateOrderRequest request) {
        return acceptedOrderService.acceptOrder(currentUserId, request);
    }

    public CancelOrderResponse cancelOrder(Long currentUserId, Long orderId) {
        return orderCancelService.cancelOrder(currentUserId, orderId);
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long currentUserId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        return OrderDetailResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrders(Long currentUserId, String market, int limit, int offset) {
        var pageable = new OffsetBasedPageRequest(offset, limit);
        List<Order> orders = (market != null)
                ? orderRepository.findAllByUserIdAndMarketSymbolOrderByCreatedAtDesc(currentUserId, market, pageable)
                : orderRepository.findAllByUserIdOrderByCreatedAtDesc(currentUserId, pageable);
        return orders.stream().map(OrderSummaryResponse::from).toList();
    }
}
