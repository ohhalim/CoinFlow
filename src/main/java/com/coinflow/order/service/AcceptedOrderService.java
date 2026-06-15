package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.dto.AcceptedOrderResponse;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.service.command.CreateOrderCommand;
import com.coinflow.order.service.command.MarketOrderCommandQueue;
import com.coinflow.order.service.lock.OrderAssetLockService;
import com.coinflow.order.service.processor.AcceptedOrderProcessor;
import com.coinflow.order.service.support.ClientOrderIdService;
import com.coinflow.order.service.support.MarketSequenceAllocator;
import com.coinflow.order.service.support.OrderCreateValidator;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AcceptedOrderService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final DomainEventRecorder eventRecorder;
    private final OrderCreateValidator orderCreateValidator;
    private final OrderAssetLockService orderAssetLockService;
    private final MarketOrderCommandQueue marketOrderCommandQueue;
    private final MarketSequenceAllocator marketSequenceAllocator;
    private final AcceptedOrderProcessor acceptedOrderProcessor;
    private final ClientOrderIdService clientOrderIdService;
    private final TransactionTemplate transactionTemplate;

    public AcceptedOrderService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            DomainEventRecorder eventRecorder,
            OrderCreateValidator orderCreateValidator,
            OrderAssetLockService orderAssetLockService,
            MarketOrderCommandQueue marketOrderCommandQueue,
            MarketSequenceAllocator marketSequenceAllocator,
            AcceptedOrderProcessor acceptedOrderProcessor,
            ClientOrderIdService clientOrderIdService,
            PlatformTransactionManager transactionManager
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.eventRecorder = eventRecorder;
        this.orderCreateValidator = orderCreateValidator;
        this.orderAssetLockService = orderAssetLockService;
        this.marketOrderCommandQueue = marketOrderCommandQueue;
        this.marketSequenceAllocator = marketSequenceAllocator;
        this.acceptedOrderProcessor = acceptedOrderProcessor;
        this.clientOrderIdService = clientOrderIdService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AcceptedOrderResponse acceptOrder(Long currentUserId, CreateOrderRequest request) {
        Market market = marketRepository.findBySymbol(request.market())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        CreateOrderCommand command = orderCreateValidator.validate(market, request);
        OrderSide side = command.side();

        Order acceptedOrder;
        try {
            acceptedOrder = transactionTemplate.execute(status -> acceptInTransaction(
                    currentUserId, request, market, command, side));
        } catch (DataIntegrityViolationException e) {
            if (request.clientOrderId() != null && clientOrderIdService.isDuplicateConstraintViolation(e)) {
                throw new ApiException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID);
            }
            throw e;
        }

        return AcceptedOrderResponse.of(acceptedOrder);
    }

    public boolean requeueAcceptedOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != OrderStatus.ACCEPTED) {
            return false;
        }
        if (marketOrderCommandQueue.isQueued(order.getMarketId(), order.getId())) {
            return false;
        }
        Market market = marketRepository.findById(order.getMarketId())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        return enqueueAcceptedOrder(market, order.getSide(), order.getId());
    }

    private Order acceptInTransaction(
            Long currentUserId,
            CreateOrderRequest request,
            Market market,
            CreateOrderCommand command,
            OrderSide side
    ) {
        clientOrderIdService.validateUnique(currentUserId, request, market, side);

        Long sequence = marketSequenceAllocator.nextSequence(market.getId());
        Wallet wallet = orderAssetLockService.lockTakerWallet(currentUserId, command);
        Order order = Order.accepted(
                currentUserId, market.getId(), market.getSymbol(),
                side, command.type(), command.timeInForce(),
                command.price(), command.quantity(),
                command.lockedAsset(), command.lockedAmount(),
                sequence, request.clientOrderId()
        );
        orderRepository.save(order);

        WalletLedger orderLockLedger = orderAssetLockService.createOrderLockLedger(wallet, order, command);
        orderAssetLockService.saveOrderLockLedger(orderLockLedger, command);
        eventRecorder.recordOrderAccepted(order);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueueAcceptedOrder(market, side, order.getId());
            }
        });
        return order;
    }

    private boolean enqueueAcceptedOrder(Market market, OrderSide side, Long orderId) {
        return marketOrderCommandQueue.submitAsync(
                market,
                side,
                orderId,
                () -> acceptedOrderProcessor.processAcceptedOrder(market, orderId)
        );
    }

}
