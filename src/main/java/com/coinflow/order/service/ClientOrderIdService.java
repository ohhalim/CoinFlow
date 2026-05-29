package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.repository.OrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ClientOrderIdService {

    private final OrderRepository orderRepository;
    private final OrderCreateStageRecorder stageRecorder;

    public ClientOrderIdService(
            OrderRepository orderRepository,
            OrderCreateStageRecorder stageRecorder
    ) {
        this.orderRepository = orderRepository;
        this.stageRecorder = stageRecorder;
    }

    public void validateUnique(Long userId, CreateOrderRequest request, Market market, OrderSide side) {
        if (request.clientOrderId() == null) {
            return;
        }
        boolean duplicatedClientOrderId = stageRecorder.record(
                market.getSymbol(), side, "client_order_id_check",
                () -> orderRepository.existsByUserIdAndClientOrderId(userId, request.clientOrderId())
        );
        if (duplicatedClientOrderId) {
            throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
        }
    }

    public boolean isDuplicateConstraintViolation(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && (
                message.contains("uq_orders_user_client_order")
                        || message.contains("uk_orders_user_client_order_id")
        );
    }
}
