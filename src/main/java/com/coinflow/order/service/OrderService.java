package com.coinflow.order.service;

import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.event.service.DomainEventRecorder;
import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.Order;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.dto.AcceptedOrderResponse;
import com.coinflow.order.dto.CancelOrderResponse;
import com.coinflow.order.dto.CreateOrderRequest;
import com.coinflow.order.dto.CreateOrderResponse;
import com.coinflow.order.dto.OrderDetailResponse;
import com.coinflow.order.dto.OrderSummaryResponse;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.order.service.command.CreateOrderCommand;
import com.coinflow.order.service.command.MarketOrderCommandQueue;
import com.coinflow.order.service.lock.MarketOrderLockService;
import com.coinflow.order.service.processor.SyncOrderProcessor;
import com.coinflow.order.service.support.ClientOrderIdService;
import com.coinflow.order.service.support.OrderCreateValidator;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import com.coinflow.common.pagination.OffsetBasedPageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class OrderService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MatchingEngine matchingEngine;
    private final DomainEventRecorder eventRecorder;
    private final MarketOrderLockService marketOrderLockService;
    private final OrderCreateValidator orderCreateValidator;
    private final ClientOrderIdService clientOrderIdService;
    private final MarketOrderCommandQueue marketOrderCommandQueue;
    private final SyncOrderProcessor syncOrderProcessor;
    private final AcceptedOrderService acceptedOrderService;
    private final TransactionTemplate transactionTemplate;

    public OrderService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            WalletRepository walletRepository,
            WalletLedgerRepository walletLedgerRepository,
            MatchingEngine matchingEngine,
            DomainEventRecorder eventRecorder,
            MarketOrderLockService marketOrderLockService,
            OrderCreateValidator orderCreateValidator,
            ClientOrderIdService clientOrderIdService,
            MarketOrderCommandQueue marketOrderCommandQueue,
            SyncOrderProcessor syncOrderProcessor,
            AcceptedOrderService acceptedOrderService,
            PlatformTransactionManager transactionManager
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.walletRepository = walletRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.matchingEngine = matchingEngine;
        this.eventRecorder = eventRecorder;
        this.marketOrderLockService = marketOrderLockService;
        this.orderCreateValidator = orderCreateValidator;
        this.clientOrderIdService = clientOrderIdService;
        this.marketOrderCommandQueue = marketOrderCommandQueue;
        this.syncOrderProcessor = syncOrderProcessor;
        this.acceptedOrderService = acceptedOrderService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CreateOrderResponse createOrder(Long currentUserId, CreateOrderRequest request) {
        long createStartedAt = System.nanoTime();

        Market market = marketRepository.findBySymbol(request.market())
                .orElseThrow(() -> new ApiException(ErrorCode.MARKET_NOT_FOUND));
        CreateOrderCommand command = orderCreateValidator.validate(market, request);
        OrderSide side = command.side();
        clientOrderIdService.validateUnique(currentUserId, request, market, side);

        return marketOrderCommandQueue.submit(
                market,
                side,
                () -> syncOrderProcessor.process(currentUserId, request, market, command, createStartedAt)
        );
    }

    public AcceptedOrderResponse acceptOrder(Long currentUserId, CreateOrderRequest request) {
        return acceptedOrderService.acceptOrder(currentUserId, request);
    }

    public CancelOrderResponse cancelOrder(Long currentUserId, Long orderId) {

        // lock 획득을 위해 먼저 marketId 조회
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

        ReentrantLock marketLock = marketOrderLockService.getLock(order.getMarketId());
        marketLock.lock();
        try {
            return transactionTemplate.execute(status -> {

                Order lockedOrder = orderRepository.findByIdAndUserIdWithLock(orderId, currentUserId)
                        .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
                if (!lockedOrder.isCancelable()) throw new ApiException(ErrorCode.ORDER_NOT_CANCELABLE);

                BigDecimal releaseAmount = lockedOrder.releasableAmount();
                Wallet wallet = walletRepository.findByUserIdAndAssetWithLock(currentUserId, lockedOrder.getLockedAsset())
                        .orElseThrow(() -> new ApiException(ErrorCode.INSUFFICIENT_BALANCE));
                wallet.unlock(releaseAmount);

                walletLedgerRepository.save(WalletLedger.create(
                        wallet, LedgerType.ORDER_CANCEL_RELEASE,
                        releaseAmount, releaseAmount.negate(),
                        orderId, null
                ));

                lockedOrder.cancel();
                eventRecorder.recordOrderCanceled(lockedOrder, lockedOrder.getLockedAsset(), releaseAmount.toPlainString());

                String marketSymbol = lockedOrder.getMarketSymbol();
                Order canceledOrder = lockedOrder;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            matchingEngine.cancelOrder(marketSymbol, canceledOrder);
                        } catch (Exception e) {
                            log.error("오더북 cancelOrder 실패: orderId={}, DB 취소 완료 but 오더북에 잔존", canceledOrder.getId(), e);
                        }
                    }
                });

                return CancelOrderResponse.of(lockedOrder, lockedOrder.getLockedAsset(), releaseAmount.toPlainString());
            });
        } finally {
            marketLock.unlock();
        }
    }

    public ReentrantLock getMarketLock(Long marketId) {
        return marketOrderLockService.getLock(marketId);
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long currentUserId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        return OrderDetailResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrders(Long currentUserId, String market, int limit, int offset) {
        var pageable = new OffsetBasedPageRequest(offset, limit);
        List<Order> orders = (market != null)
                ? orderRepository.findAllByUserIdAndMarketSymbolOrderByCreatedAtDesc(currentUserId, market, pageable)
                : orderRepository.findAllByUserIdOrderByCreatedAtDesc(currentUserId, pageable);
        return orders.stream().map(OrderSummaryResponse::from).toList();
    }
}
