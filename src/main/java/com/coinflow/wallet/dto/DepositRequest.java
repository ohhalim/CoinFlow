package com.coinflow.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DepositRequest(
        @NotBlank String asset,
        @NotBlank
        @Pattern(regexp = "^\\d+(\\.\\d+)?$", message = "amount must be a positive number")
        String amount
) {}
