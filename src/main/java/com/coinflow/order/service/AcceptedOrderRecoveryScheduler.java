package com.coinflow.order.service;

import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class AcceptedOrderRecoveryScheduler {

    private final OrderRepository orderRepository;
    private final AcceptedOrderService acceptedOrderService;
    private final boolean enabled;
    private final long thresholdSeconds;

    public AcceptedOrderRecoveryScheduler(
            OrderRepository orderRepository,
            AcceptedOrderService acceptedOrderService,
            @Value("${coinflow.order.accepted-recovery.enabled:true}") boolean enabled,
            @Value("${coinflow.order.accepted-recovery.threshold-seconds:5}") long thresholdSeconds
    ) {
        this.orderRepository = orderRepository;
        this.acceptedOrderService = acceptedOrderService;
        this.enabled = enabled;
        this.thresholdSeconds = thresholdSeconds;
    }

    @Scheduled(fixedDelayString = "${coinflow.order.accepted-recovery.fixed-delay-ms:5000}")
    public void recoverAcceptedOrders() {
        if (!enabled) {
            return;
        }
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(thresholdSeconds);
        List<Order> orders = orderRepository.findTop100ByStatusAndCreatedAtBeforeOrderBySequenceAsc(
                OrderStatus.ACCEPTED,
                threshold
        );
        for (Order order : orders) {
            try {
                acceptedOrderService.requeueAcceptedOrder(order.getId());
            } catch (Exception e) {
                log.warn("Accepted order recovery failed. orderId={}, error={}", order.getId(), e.getMessage(), e);
            }
        }
    }
}
