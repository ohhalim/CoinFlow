package com.coinflow.order.service;

import com.coinflow.order.dto.AcceptedOrderResponse;
import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.OrderDetailResponse;
import com.coinflow.order.dto.OrderSummaryResponse;
import com.coinflow.order.service.cancel.OrderCancelService;
import com.coinflow.order.service.query.OrderQueryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderService {

    private final AcceptedOrderService acceptedOrderService;
    private final OrderCancelService orderCancelService;
    private final OrderQueryService orderQueryService;

    public OrderService(
            AcceptedOrderService acceptedOrderService,
            OrderCancelService orderCancelService,
            OrderQueryService orderQueryService
    ) {
        this.acceptedOrderService = acceptedOrderService;
        this.orderCancelService = orderCancelService;
        this.orderQueryService = orderQueryService;
    }

    public AcceptedOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {
        return acceptedOrderService.acceptOrder(currentUserId, request);
    }

    public CancelOrderResponse cancelOrder(Long currentUserId, Long orderId) {
        return orderCancelService.cancelOrder(currentUserId, orderId);
    }

    public OrderDetailResponse getOrder(Long currentUserId, Long orderId) {
        return orderQueryService.getOrder(currentUserId, orderId);
    }

    public List<OrderSummaryResponse> getOrders(Long currentUserId, String market, int limit, int offset) {
        return orderQueryService.getOrders(currentUserId, market, limit, offset);
    }
}
