package com.coinflow.integration;

import com.coinflow.auth.repository.UserRepository;
import com.coinflow.event.repository.DomainEventRepository;
import com.coinflow.event.service.OutboxPublisher;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.support.IntegrityAssertions;
import com.coinflow.support.TestcontainersConfig;
import com.coinflow.trade.repository.TradeRepository;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import com.coinflow.websocket.dto.OrderBookSnapshotMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("rawtypes")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coinflow.websocket.orderbook.enabled=true",
                "coinflow.websocket.trade-feed.enabled=false",
                "coinflow.websocket.orderbook.coalesce-delay-ms=10"
        }
)
@EmbeddedKafka(
        partitions = 4,
        topics = {"coinflow.order.events", "coinflow.trade.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import(TestcontainersConfig.class)
class WebSocketOrderBookBroadcastIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private MarketRepository marketRepository;
    @Autowired private MatchingEngine matchingEngine;
    @Autowired private OutboxPublisher outboxPublisher;

    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void setUp() {
        domainEventRepository.deleteAllInBatch();
        walletLedgerRepository.deleteAllInBatch();
        tradeRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        matchingEngine.clearAll();
        reset(messagingTemplate);
    }

    @AfterEach
    void assertIntegrity() {
        new IntegrityAssertions(
                marketRepository,
                orderRepository,
                tradeRepository,
                walletRepository,
                walletLedgerRepository,
                matchingEngine
        ).assertAll();
    }

    @Test
    void 주문_생성_후_오더북_snapshot을_broadcast한다() {
        String sellerToken = signupAndLogin("orderbook-create-seller@example.com");
        depositBtc("orderbook-create-seller@example.com", new BigDecimal("0.001"));

        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);
        outboxPublisher.publishPendingEvents();

        OrderBookSnapshotMessage message = awaitOrderBookMessage();

        assertThat(message.market()).isEqualTo("BTC-KRW");
        assertThat(message.bids()).isEmpty();
        assertThat(message.asks()).containsExactly(
                new OrderBookSnapshotMessage.PriceLevel("100000000", "0.0001")
        );
    }

    @Test
    void 체결_후_오더북_snapshot을_broadcast한다() {
        String buyerToken = signupAndLogin("orderbook-fill-buyer@example.com");
        String sellerToken = signupAndLogin("orderbook-fill-seller@example.com");
        depositKrw("orderbook-fill-buyer@example.com", new BigDecimal("10000000"));
        depositBtc("orderbook-fill-seller@example.com", new BigDecimal("0.001"));

        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);
        outboxPublisher.publishPendingEvents();
        awaitOrderBookMessage();
        reset(messagingTemplate);

        createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        outboxPublisher.publishPendingEvents();

        OrderBookSnapshotMessage message = awaitOrderBookMessage();
        assertThat(message.market()).isEqualTo("BTC-KRW");
        assertThat(message.bids()).isEmpty();
        assertThat(message.asks()).isEmpty();
    }

    @Test
    void 주문_취소_후_오더북_snapshot을_broadcast한다() {
        String sellerToken = signupAndLogin("orderbook-cancel-seller@example.com");
        depositBtc("orderbook-cancel-seller@example.com", new BigDecimal("0.001"));

        Long orderId = createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC",
                "100000000", "0.0001", null);
        outboxPublisher.publishPendingEvents();
        awaitOrderBookMessage();
        reset(messagingTemplate);

        cancelOrder(sellerToken, orderId);
        outboxPublisher.publishPendingEvents();

        OrderBookSnapshotMessage message = awaitOrderBookMessage();
        assertThat(message.market()).isEqualTo("BTC-KRW");
        assertThat(message.bids()).isEmpty();
        assertThat(message.asks()).isEmpty();
    }

    private OrderBookSnapshotMessage awaitOrderBookMessage() {
        ArgumentCaptor<OrderBookSnapshotMessage> captor = ArgumentCaptor.forClass(OrderBookSnapshotMessage.class);
        await().untilAsserted(() -> verify(messagingTemplate, atLeastOnce())
                .convertAndSend(eq("/topic/orderbook/BTC-KRW"), captor.capture()));
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private String signupAndLogin(String email) {
        restTemplate.postForEntity(
                "/api/v1/auth/signup",
                Map.of("email", email, "password", "password1234", "nickname", "tester"),
                Map.class
        );
        var loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("email", email, "password", "password1234"),
                Map.class
        );
        return (String) loginResponse.getBody().get("accessToken");
    }

    private void depositKrw(String email, BigDecimal amount) {
        deposit(email, "KRW", amount);
    }

    private void depositBtc(String email, BigDecimal amount) {
        deposit(email, "BTC", amount);
    }

    private void deposit(String email, String asset, BigDecimal amount) {
        var user = userRepository.findByEmail(email).orElseThrow();
        Wallet wallet = walletRepository.findAllByUserId(user.getId()).stream()
                .filter(w -> w.getAsset().equals(asset))
                .findFirst().orElseThrow();
        wallet.deposit(amount);
        walletRepository.save(wallet);
    }

    private Long createOrder(String token, String market, String side, String type,
                             String timeInForce, String price, String quantity,
                             String clientOrderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var body = new java.util.HashMap<String, String>();
        body.put("market", market);
        body.put("side", side);
        body.put("type", type);
        body.put("timeInForce", timeInForce);
        body.put("price", price);
        body.put("quantity", quantity);
        if (clientOrderId != null) body.put("clientOrderId", clientOrderId);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        return ((Number) response.getBody().get("orderId")).longValue();
    }

    private void cancelOrder(String token, Long orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange(
                "/api/v1/orders/%d/cancel".formatted(orderId),
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class
        );
    }
}
