package com.coinflow.order.service.support;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderType;
import com.coinflow.order.domain.TimeInForce;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.service.command.CreateOrderCommand;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class OrderCreateValidator {

    public CreateOrderCommand validate(Market market, CreateOrderRequest request) {
        if (!market.isActive()) throw new ApiException(ErrorCode.MARKET_NOT_ACTIVE);
        if (market.isCancelOnly()) throw new ApiException(ErrorCode.MARKET_CANCEL_ONLY);

        OrderSide side = parseSide(request.side());
        OrderType type = parseType(request.type());
        TimeInForce tif = parseTif(request.timeInForce());
        BigDecimal price = parseBigDecimal(request.price(), ErrorCode.INVALID_PRICE);
        BigDecimal quantity = parseBigDecimal(request.quantity(), ErrorCode.INVALID_QUANTITY);

        validatePolicy(market, price, quantity);

        String lockedAsset;
        BigDecimal lockedAmount;
        if (side == OrderSide.BUY) {
            lockedAsset = market.getQuoteAsset();
            lockedAmount = price.multiply(quantity).setScale(market.getAmountScale(), RoundingMode.CEILING);
        } else {
            lockedAsset = market.getBaseAsset();
            lockedAmount = quantity;
        }

        return new CreateOrderCommand(market, side, type, tif, price, quantity, lockedAsset, lockedAmount);
    }

    private void validatePolicy(Market market, BigDecimal price, BigDecimal quantity) {
        if (price.compareTo(BigDecimal.ZERO) <= 0) throw new ApiException(ErrorCode.INVALID_PRICE);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) throw new ApiException(ErrorCode.INVALID_QUANTITY);
        if (price.remainder(market.getTickSize()).compareTo(BigDecimal.ZERO) != 0) {
            throw new ApiException(ErrorCode.INVALID_TICK_SIZE);
        }
        if (quantity.remainder(market.getStepSize()).compareTo(BigDecimal.ZERO) != 0) {
            throw new ApiException(ErrorCode.INVALID_STEP_SIZE);
        }
        if (quantity.compareTo(market.getMinOrderQuantity()) < 0) {
            throw new ApiException(ErrorCode.MIN_ORDER_QUANTITY_NOT_MET);
        }
        if (price.multiply(quantity).compareTo(market.getMinOrderAmount()) < 0) {
            throw new ApiException(ErrorCode.MIN_ORDER_AMOUNT_NOT_MET);
        }
    }

    private OrderSide parseSide(String value) {
        try {
            return OrderSide.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_ORDER_SIDE);
        }
    }

    private OrderType parseType(String value) {
        try {
            return OrderType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_ORDER_TYPE);
        }
    }

    private TimeInForce parseTif(String value) {
        try {
            return TimeInForce.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private BigDecimal parseBigDecimal(String value, ErrorCode errorCode) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ApiException(errorCode);
        }
    }
}
