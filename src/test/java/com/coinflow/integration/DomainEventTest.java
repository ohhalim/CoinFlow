package com.coinflow.integration;

import com.coinflow.auth.repository.UserRepository;
import com.coinflow.event.domain.DomainEventType;
import com.coinflow.event.repository.DomainEventRepository;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.support.IntegrityAssertions;
import com.coinflow.support.TestcontainersConfig;
import com.coinflow.trade.repository.TradeRepository;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class DomainEventTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private MarketRepository marketRepository;
    @Autowired private MatchingEngine matchingEngine;

    @BeforeEach
    void setUp() {
        domainEventRepository.deleteAllInBatch();
        walletLedgerRepository.deleteAllInBatch();
        tradeRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        matchingEngine.clearAll();
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
    void 주문_생성_시_ORDER_ACCEPTED_이벤트_저장() {
        String token = signupAndLogin("evt001@example.com");
        depositKrw("evt001@example.com", new BigDecimal("10000000"));

        var response = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) response.getBody().get("orderId")).longValue();

        var events = domainEventRepository.findAllByAggregateTypeAndAggregateId("ORDER", orderId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(DomainEventType.ORDER_ACCEPTED);
        assertThat(events.get(0).getPayload()).contains("orderId");
        assertThat(events.get(0).isPublished()).isFalse();
    }

    @Test
    void 전량_체결_시_ORDER_FILLED_TRADE_CREATED_SETTLEMENT_COMPLETED_이벤트_저장() {
        String buyerToken  = signupAndLogin("evt002-buyer@example.com");
        String sellerToken = signupAndLogin("evt002-seller@example.com");
        depositKrw("evt002-buyer@example.com",  new BigDecimal("10000000"));
        depositBtc("evt002-seller@example.com", new BigDecimal("0.001"));

        var buyResponse = createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long buyOrderId = ((Number) buyResponse.getBody().get("orderId")).longValue();

        var sellResponse = createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long sellOrderId = ((Number) sellResponse.getBody().get("orderId")).longValue();

        // 매수 주문: ORDER_ACCEPTED + ORDER_FILLED
        var buyEvents = domainEventRepository.findAllByAggregateTypeAndAggregateId("ORDER", buyOrderId);
        var buyEventTypes = buyEvents.stream().map(e -> e.getEventType()).toList();
        assertThat(buyEventTypes).containsExactlyInAnyOrder(
                DomainEventType.ORDER_ACCEPTED,
                DomainEventType.ORDER_FILLED
        );

        // 매도 주문: ORDER_ACCEPTED + ORDER_FILLED
        var sellEvents = domainEventRepository.findAllByAggregateTypeAndAggregateId("ORDER", sellOrderId);
        var sellEventTypes = sellEvents.stream().map(e -> e.getEventType()).toList();
        assertThat(sellEventTypes).containsExactlyInAnyOrder(
                DomainEventType.ORDER_ACCEPTED,
                DomainEventType.ORDER_FILLED
        );

        // TRADE: TRADE_CREATED + SETTLEMENT_COMPLETED
        var trades = (List<?>) sellResponse.getBody().get("trades");
        Long tradeId = ((Number) ((Map<?, ?>) trades.get(0)).get("tradeId")).longValue();
        var tradeEvents = domainEventRepository.findAllByAggregateTypeAndAggregateId("TRADE", tradeId);
        var tradeEventTypes = tradeEvents.stream().map(e -> e.getEventType()).toList();
        assertThat(tradeEventTypes).containsExactlyInAnyOrder(
                DomainEventType.TRADE_CREATED,
                DomainEventType.SETTLEMENT_COMPLETED
        );
    }

    @Test
    void 부분_체결_시_ORDER_PARTIALLY_FILLED_이벤트_저장() {
        String buyerToken  = signupAndLogin("evt003-buyer@example.com");
        String sellerToken = signupAndLogin("evt003-seller@example.com");
        depositKrw("evt003-buyer@example.com",  new BigDecimal("100000000"));
        depositBtc("evt003-seller@example.com", new BigDecimal("0.001"));

        // BUY 0.001, SELL 0.0001 → 부분 체결
        var buyResponse = createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.001", null);
        Long buyOrderId = ((Number) buyResponse.getBody().get("orderId")).longValue();

        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

        var buyEvents = domainEventRepository.findAllByAggregateTypeAndAggregateId("ORDER", buyOrderId);
        var buyEventTypes = buyEvents.stream().map(e -> e.getEventType()).toList();

        assertThat(buyEventTypes).contains(DomainEventType.ORDER_ACCEPTED);
        assertThat(buyEventTypes).contains(DomainEventType.ORDER_PARTIALLY_FILLED);
        assertThat(buyEventTypes).doesNotContain(DomainEventType.ORDER_FILLED);
    }

    @Test
    void 주문_취소_시_ORDER_CANCELED_이벤트_저장() {
        String token = signupAndLogin("evt004@example.com");
        depositKrw("evt004@example.com", new BigDecimal("10000000"));

        var createResponse = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();

        cancelOrder(token, orderId);

        var events = domainEventRepository.findAllByAggregateTypeAndAggregateId("ORDER", orderId);
        var eventTypes = events.stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).containsExactlyInAnyOrder(
                DomainEventType.ORDER_ACCEPTED,
                DomainEventType.ORDER_CANCELED
        );

        var cancelEvent = events.stream()
                .filter(e -> e.getEventType() == DomainEventType.ORDER_CANCELED)
                .findFirst().orElseThrow();
        assertThat(cancelEvent.getPayload()).contains("releasedAsset");
        assertThat(cancelEvent.getPayload()).contains("KRW");
    }

    // ── helpers ───────────────────────────────────────────────────────

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

    private ResponseEntity<Map> createOrder(String token, String market, String side, String type,
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
        return restTemplate.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> cancelOrder(String token, Long orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/orders/" + orderId + "/cancel", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }
}
