package com.coinflow.integration;

import com.coinflow.auth.repository.UserRepository;
import com.coinflow.event.repository.DomainEventRepository;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.OrderStatus;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.matching.OrderBookRecoveryService;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class OrderBookRecoveryTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private MarketRepository marketRepository;
    @Autowired private MatchingEngine matchingEngine;
    @Autowired private OrderBookRecoveryService orderBookRecoveryService;

    @BeforeEach
    void setUp() {
        matchingEngine.clearAll();
        domainEventRepository.deleteAllInBatch();
        walletLedgerRepository.deleteAllInBatch();
        tradeRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
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
    void 오더북_rebuild는_DB의_OPEN_PARTIALLY_FILLED_주문만_복원한다() {
        String buyerToken = signupAndLogin("rebuild001-buyer@example.com");
        String sellerToken = signupAndLogin("rebuild001-seller@example.com");
        depositKrw("rebuild001-buyer@example.com", new BigDecimal("50000"));
        depositBtc("rebuild001-seller@example.com", new BigDecimal("0.001"));

        var openBuyResponse = createOrder(
                buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "90000000", "0.0001", null
        );
        Long openBuyOrderId = orderId(openBuyResponse);

        var partialBuyResponse = createOrder(
                buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0002", null
        );
        Long partialBuyOrderId = orderId(partialBuyResponse);

        var filledSellResponse = createOrder(
                sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null
        );
        Long filledSellOrderId = orderId(filledSellResponse);

        var canceledSellResponse = createOrder(
                sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "120000000", "0.0001", null
        );
        Long canceledSellOrderId = orderId(canceledSellResponse);
        cancelOrder(sellerToken, canceledSellOrderId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(orderRepository.findById(openBuyOrderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.OPEN);
            assertThat(orderRepository.findById(partialBuyOrderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
            assertThat(orderRepository.findById(filledSellOrderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
            assertThat(orderRepository.findById(canceledSellOrderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELED);
        });

        matchingEngine.clearAll();
        assertThat(matchingEngine.getBuySide("BTC-KRW")).isEmpty();
        assertThat(matchingEngine.getSellSide("BTC-KRW")).isEmpty();

        rebuildBtcKrwOrderBook();

        List<Long> buyOrderIds = matchingEngine.getBuySide("BTC-KRW").stream()
                .map(entry -> entry.orderId())
                .toList();
        List<Long> sellOrderIds = matchingEngine.getSellSide("BTC-KRW").stream()
                .map(entry -> entry.orderId())
                .toList();

        assertThat(buyOrderIds).containsExactly(partialBuyOrderId, openBuyOrderId);
        assertThat(sellOrderIds).isEmpty();
        assertThat(buyOrderIds).doesNotContain(filledSellOrderId, canceledSellOrderId);
    }

    @Test
    void 오더북_rebuild_이후_기존_OPEN_주문과_신규_주문이_정상_매칭된다() {
        String sellerToken = signupAndLogin("rebuild002-seller@example.com");
        String buyerToken = signupAndLogin("rebuild002-buyer@example.com");
        depositBtc("rebuild002-seller@example.com", new BigDecimal("0.001"));
        depositKrw("rebuild002-buyer@example.com", new BigDecimal("20000"));

        var sellResponse = createOrder(
                sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0002", null
        );
        Long sellOrderId = orderId(sellResponse);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(orderRepository.findById(sellOrderId).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.OPEN));

        matchingEngine.clearAll();
        assertThat(matchingEngine.getSellSide("BTC-KRW")).isEmpty();

        rebuildBtcKrwOrderBook();
        assertThat(matchingEngine.getSellSide("BTC-KRW"))
                .extracting(entry -> entry.orderId())
                .containsExactly(sellOrderId);

        var buyResponse = createOrder(
                buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null
        );
        Long rebuildBuyOrderId = ((Number) buyResponse.getBody().get("orderId")).longValue();

        assertThat(buyResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(orderRepository.findById(rebuildBuyOrderId).orElseThrow().getStatus().name()).isEqualTo("FILLED");
            assertThat(tradeRepository.count()).isEqualTo(1);
        });

        var sellerOrder = orderRepository.findById(sellOrderId).orElseThrow();
        assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(sellerOrder.getRemainingQuantity()).isEqualByComparingTo("0.0001");
        assertThat(matchingEngine.getSellSide("BTC-KRW"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.orderId()).isEqualTo(sellOrderId);
                    assertThat(entry.remainingQuantity()).isEqualByComparingTo("0.0001");
                });
    }

    private void rebuildBtcKrwOrderBook() {
        var market = marketRepository.findBySymbol("BTC-KRW").orElseThrow();
        orderBookRecoveryService.rebuildAfterApplyFailure(market.getId());
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

    private Long orderId(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return ((Number) response.getBody().get("orderId")).longValue();
    }

    private ResponseEntity<Map> createOrder(
            String token,
            String market,
            String side,
            String type,
            String timeInForce,
            String price,
            String quantity,
            String clientOrderId
    ) {
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
        return restTemplate.exchange(
                "/api/v1/orders/" + orderId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class
        );
    }
}
