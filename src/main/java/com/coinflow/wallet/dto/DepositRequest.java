package com.coinflow.wallet.dto;

public record DepositRequest(
        String asset,
        String amount
) {}
