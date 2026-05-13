package com.coinflow.order.api;

import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.CreateOrderResponse;
import com.coinflow.order.dto.OrderDetailResponse;
import com.coinflow.order.dto.OrderSummaryResponse;
import com.coinflow.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateOrderResponse createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return orderService.createOrder(userId, request);
    }

    @PostMapping("/{id}/cancel")
    public CancelOrderResponse cancelOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return orderService.cancelOrder(userId, id);
    }

    @GetMapping
    public List<OrderSummaryResponse> getOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String market
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return orderService.getOrders(userId, market);
    }

    @GetMapping("/{id}")
    public OrderDetailResponse getOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return orderService.getOrder(userId, id);
    }
}
