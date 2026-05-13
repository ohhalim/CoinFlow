package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSequence;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderType;
import com.coinflow.order.domain.TimeInForce;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.CreateOrderResponse;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.repository.OrderSequenceRepository;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final OrderSequenceRepository orderSequenceRepository;
    private final WalletRepository walletRepository;

    @Transactional
    public CreateOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {

        // 1. market 조회
        Market market = marketRepository.findBySymbol(request.market())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));

        // 2. market 상태 검증
        if (!market.isActive()) throw new ApiException(ErrorCode.MARKET_NOT_ACTIVE);
        if (market.isCancelOnly()) throw new ApiException(ErrorCode.MARKET_CANCEL_ONLY);

        // 3. enum 파싱
        OrderSide side = parseSide(request.side());
        OrderType type = parseType(request.type());
        TimeInForce tif = parseTif(request.timeInForce());

        // 4. price, quantity 파싱
        BigDecimal price    = parseBigDecimal(request.price(),    ErrorCode.INVALID_PRICE);
        BigDecimal quantity = parseBigDecimal(request.quantity(), ErrorCode.INVALID_QUANTITY);
        if (price.compareTo(BigDecimal.ZERO) <= 0)    throw new ApiException(ErrorCode.INVALID_PRICE);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) throw new ApiException(ErrorCode.INVALID_QUANTITY);

        // 5. 주문 정책 검증
        if (price.remainder(market.getTickSize()).compareTo(BigDecimal.ZERO) != 0)
            throw new ApiException(ErrorCode.INVALID_TICK_SIZE);
        if (quantity.remainder(market.getStepSize()).compareTo(BigDecimal.ZERO) != 0)
            throw new ApiException(ErrorCode.INVALID_STEP_SIZE);
        if (quantity.compareTo(market.getMinOrderQuantity()) < 0)
            throw new ApiException(ErrorCode.MIN_ORDER_QUANTITY_NOT_MET);
        if (price.multiply(quantity).compareTo(market.getMinOrderAmount()) < 0)
            throw new ApiException(ErrorCode.MIN_ORDER_AMOUNT_NOT_MET);

        // 6. clientOrderId 중복 검증
        if (request.clientOrderId() != null &&
                orderRepository.existsByUserIdAndClientOrderId(currentUserId, request.clientOrderId())) {
            throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
        }

        // 7. sequence 발급
        OrderSequence seq = orderSequenceRepository.findByMarketIdWithLock(market.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        Long sequence = seq.nextSequence();

        // 8. lockedAsset, lockedAmount 계산
        String lockedAsset;
        BigDecimal lockedAmount;
        if (side == OrderSide.BUY) {
            lockedAsset = market.getQuoteAsset();
            lockedAmount = price.multiply(quantity)
                    .setScale(market.getAmountScale(), RoundingMode.CEILING);
        } else {
            lockedAsset = market.getBaseAsset();
            lockedAmount = quantity;
        }

        // 9. wallet lock
        Wallet wallet = walletRepository.findByUserIdAndAssetWithLock(currentUserId, lockedAsset)
                .orElseThrow(() -> new ApiException(ErrorCode.INSUFFICIENT_BALANCE));
        if (wallet.getAvailableBalance().compareTo(lockedAmount) < 0)
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
        wallet.lock(lockedAmount);

        // 10. order 저장
        Order order = Order.create(
                currentUserId, market.getId(), market.getSymbol(),
                side, type, tif,
                price, quantity,
                lockedAsset, lockedAmount,
                sequence, request.clientOrderId()
        );
        orderRepository.save(order);

        // 11. 반환 (매칭엔진은 추후 추가)
        return CreateOrderResponse.of(order, List.of());
    }

    private OrderSide parseSide(String value) {
        try { return OrderSide.valueOf(value); }
        catch (IllegalArgumentException e) { throw new ApiException(ErrorCode.INVALID_ORDER_SIDE); }
    }

    private OrderType parseType(String value) {
        try { return OrderType.valueOf(value); }
        catch (IllegalArgumentException e) { throw new ApiException(ErrorCode.INVALID_ORDER_TYPE); }
    }

    private TimeInForce parseTif(String value) {
        try { return TimeInForce.valueOf(value); }
        catch (IllegalArgumentException e) { throw new ApiException(ErrorCode.INVALID_REQUEST); }
    }

    private BigDecimal parseBigDecimal(String value, ErrorCode errorCode) {
        try { return new BigDecimal(value); }
        catch (NumberFormatException e) { throw new ApiException(errorCode); }
    }
}
