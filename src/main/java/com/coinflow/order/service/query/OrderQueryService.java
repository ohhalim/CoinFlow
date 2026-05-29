package com.coinflow.order.service.query;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.common.pagination.OffsetBasedPageRequest;
import com.coinflow.order.domain.Order;
import com.coinflow.order.dto.OrderDetailResponse;
import com.coinflow.order.dto.OrderSummaryResponse;
import com.coinflow.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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
