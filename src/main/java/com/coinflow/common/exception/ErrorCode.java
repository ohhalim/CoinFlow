package com.coinflow.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "Duplicate email"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials"),

    MARKET_NOT_FOUND(HttpStatus.NOT_FOUND, "MARKET_NOT_FOUND", "Market not found"),
    MARKET_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "MARKET_NOT_ACTIVE", "Market is not active"),
    MARKET_CANCEL_ONLY(HttpStatus.BAD_REQUEST, "MARKET_CANCEL_ONLY", "Market is cancel only"),

    INVALID_ORDER_SIDE(HttpStatus.BAD_REQUEST, "INVALID_ORDER_SIDE", "Invalid order side"),
    INVALID_ORDER_TYPE(HttpStatus.BAD_REQUEST, "INVALID_ORDER_TYPE", "Invalid order type"),
    INVALID_PRICE(HttpStatus.BAD_REQUEST, "INVALID_PRICE", "Invalid price"),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Invalid quantity"),
    INVALID_TICK_SIZE(HttpStatus.BAD_REQUEST, "INVALID_TICK_SIZE", "Price does not match tick size"),
    INVALID_STEP_SIZE(HttpStatus.BAD_REQUEST, "INVALID_STEP_SIZE", "Quantity does not match step size"),
    MIN_ORDER_QUANTITY_NOT_MET(HttpStatus.BAD_REQUEST, "MIN_ORDER_QUANTITY_NOT_MET", "Quantity is below minimum"),
    MIN_ORDER_AMOUNT_NOT_MET(HttpStatus.BAD_REQUEST, "MIN_ORDER_AMOUNT_NOT_MET", "Order amount is below minimum"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Amount must be greater than zero"),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "INSUFFICIENT_BALANCE", "Insufficient balance"),
    DUPLICATE_CLIENT_ORDER_ID(HttpStatus.CONFLICT, "DUPLICATE_CLIENT_ORDER_ID", "Duplicate client order id"),

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found"),
    ORDER_NOT_CANCELABLE(HttpStatus.BAD_REQUEST, "ORDER_NOT_CANCELABLE", "Order is not cancelable"),
    SELF_TRADE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "SELF_TRADE_NOT_ALLOWED", "Self trade is not allowed");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
