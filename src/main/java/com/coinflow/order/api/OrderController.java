package com.coinflow.order.api;

import com.coinflow.order.dto.AcceptedOrderResponse;
import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.OrderDetailResponse;
import com.coinflow.order.dto.OrderSummaryResponse;
import com.coinflow.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<AcceptedOrderResponse> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(orderService.createOrder(userId, request));
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
            @RequestParam(required = false) String market,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return orderService.getOrders(userId, market, limit, offset);
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
