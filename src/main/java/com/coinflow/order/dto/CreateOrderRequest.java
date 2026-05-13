package com.coinflow.order.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(
        @NotBlank String market,
        @NotBlank String side,
        @NotBlank String type,
        @NotBlank String timeInForce,
        @NotBlank String price,
        @NotBlank String quantity,
        String clientOrderId
) {
}
