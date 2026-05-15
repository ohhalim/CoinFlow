package com.coinflow.query;

import com.coinflow.auth.repository.UserRepository;
import com.coinflow.event.repository.DomainEventRepository;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.support.TestcontainersConfig;
import com.coinflow.trade.repository.TradeRepository;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
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

@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class QueryApiTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private MatchingEngine matchingEngine;

    @BeforeEach
    void setUp() {
        matchingEngine.clearAll();
        walletLedgerRepository.deleteAll();
        domainEventRepository.deleteAll();
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── QRY-001 시장 목록 조회 ────────────────────────────────────────

    @Test
    void 시장_목록_조회_성공() {
        var response = restTemplate.getForEntity("/api/v1/markets", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> markets = (List<Map<?, ?>>) response.getBody();
        assertThat(markets).isNotEmpty();
        assertThat(markets).allMatch(m ->
                m.containsKey("market") &&
                m.containsKey("baseAsset") &&
                m.containsKey("quoteAsset") &&
                m.containsKey("amountScale") &&
                m.containsKey("tickSize") &&
                m.containsKey("cancelOnly") &&
                "ACTIVE".equals(m.get("status"))
        );
    }

    // ── QRY-002 오더북 조회 ───────────────────────────────────────────

    @Test
    void 오더북_조회_빈_오더북() {
        var response = restTemplate.getForEntity("/api/v1/markets/BTC-KRW/orderbook", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("market");
        assertThat(response.getBody()).containsKey("bids");
        assertThat(response.getBody()).containsKey("asks");
        assertThat((List<?>) response.getBody().get("bids")).isEmpty();
        assertThat((List<?>) response.getBody().get("asks")).isEmpty();
    }

    @Test
    void 오더북_조회_주문_등록_후() {
        String buyerToken = signupAndLogin("query001a@example.com");
        String sellerToken = signupAndLogin("query001b@example.com");
        depositKrw("query001a@example.com", new BigDecimal("10000000"));
        depositBtc("query001b@example.com", new BigDecimal("0.001"));

        createOrder(buyerToken, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(sellerToken, "BTC-KRW", "SELL", "110000000", "0.0001");

        var response = restTemplate.getForEntity("/api/v1/markets/BTC-KRW/orderbook", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> bids = (List<Map<?, ?>>) response.getBody().get("bids");
        List<Map<?, ?>> asks = (List<Map<?, ?>>) response.getBody().get("asks");
        assertThat(bids).hasSize(1);
        assertThat(asks).hasSize(1);
        assertThat(bids.get(0).get("price")).isEqualTo("100000000");
        assertThat(asks.get(0).get("price")).isEqualTo("110000000");
    }

    @Test
    void 오더북_BUY_가격_내림차순() {
        String token = signupAndLogin("query002@example.com");
        depositKrw("query002@example.com", new BigDecimal("100000000"));

        createOrder(token, "BTC-KRW", "BUY", "90000000", "0.0001");
        createOrder(token, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(token, "BTC-KRW", "BUY", "80000000", "0.0001");

        var response = restTemplate.getForEntity("/api/v1/markets/BTC-KRW/orderbook", Map.class);
        List<Map<?, ?>> bids = (List<Map<?, ?>>) response.getBody().get("bids");

        assertThat(bids).hasSize(3);
        assertThat(bids.get(0).get("price")).isEqualTo("100000000");
        assertThat(bids.get(1).get("price")).isEqualTo("90000000");
        assertThat(bids.get(2).get("price")).isEqualTo("80000000");
    }

    @Test
    void 오더북_같은_가격은_합산하고_depth를_적용() {
        String token = signupAndLogin("query002b@example.com");
        depositKrw("query002b@example.com", new BigDecimal("30000"));

        createOrder(token, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(token, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(token, "BTC-KRW", "BUY", "90000000", "0.0001");

        var response = restTemplate.getForEntity("/api/v1/markets/BTC-KRW/orderbook?depth=1", Map.class);
        List<Map<?, ?>> bids = (List<Map<?, ?>>) response.getBody().get("bids");

        assertThat(bids).hasSize(1);
        assertThat(bids.get(0).get("price")).isEqualTo("100000000");
        assertThat(bids.get(0).get("quantity")).isEqualTo("0.0002");
    }

    // ── QRY-003 체결 내역 조회 ────────────────────────────────────────

    @Test
    void 시장_체결_내역_조회() {
        String buyerToken = signupAndLogin("query003a@example.com");
        String sellerToken = signupAndLogin("query003b@example.com");
        depositKrw("query003a@example.com", new BigDecimal("10000000"));
        depositBtc("query003b@example.com", new BigDecimal("0.001"));

        createOrder(buyerToken, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");

        var response = restTemplate.getForEntity("/api/v1/markets/BTC-KRW/trades", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> trades = (List<Map<?, ?>>) response.getBody();
        assertThat(trades).isNotEmpty();
        Map<?, ?> trade = trades.get(0);
        assertThat((Map) trade).containsKeys("tradeId", "market", "price", "quantity", "quoteAmount", "tradedAt");
        assertThat(trade.get("price")).isEqualTo("100000000");
        assertThat(trade.get("quantity")).isEqualTo("0.0001");
    }

    @Test
    void 시장_체결_내역_limit_적용() {
        String buyerToken = signupAndLogin("query004a@example.com");
        String sellerToken = signupAndLogin("query004b@example.com");
        depositKrw("query004a@example.com", new BigDecimal("100000000"));
        depositBtc("query004b@example.com", new BigDecimal("0.01"));

        for (int i = 0; i < 5; i++) {
            createOrder(buyerToken, "BTC-KRW", "BUY", "100000000", "0.0001");
            createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");
        }

        var response = restTemplate.getForEntity("/api/v1/markets/BTC-KRW/trades?limit=3", List.class);
        assertThat(((List<?>) response.getBody())).hasSizeLessThanOrEqualTo(3);
    }

    // ── TRD-003 사용자 fill 조회 ──────────────────────────────────────

    @Test
    void 사용자_fill_조회() {
        String buyerToken = signupAndLogin("query005a@example.com");
        String sellerToken = signupAndLogin("query005b@example.com");
        depositKrw("query005a@example.com", new BigDecimal("10000000"));
        depositBtc("query005b@example.com", new BigDecimal("0.001"));

        createOrder(buyerToken, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");

        var buyerFills = getFills(buyerToken, null);
        assertThat(buyerFills.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> fills = (List<Map<?, ?>>) buyerFills.getBody();
        assertThat(fills).isNotEmpty();
        Map<?, ?> fill = fills.get(0);
        assertThat((Map) fill).containsKeys(
                "tradeId", "market", "orderId", "side", "price", "quantity", "quoteAmount", "liquidity", "settled", "tradedAt"
        );
        assertThat(fill.get("side")).isEqualTo("BUY");
        assertThat(fill.get("liquidity")).isEqualTo("M");
        assertThat(fill.get("settled")).isEqualTo(true);
    }

    @Test
    void 사용자_fill_조회_market_필터() {
        String buyerToken = signupAndLogin("query006a@example.com");
        String sellerToken = signupAndLogin("query006b@example.com");
        depositKrw("query006a@example.com", new BigDecimal("10000000"));
        depositBtc("query006b@example.com", new BigDecimal("0.001"));

        createOrder(buyerToken, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");

        var response = getFills(buyerToken, "BTC-KRW");
        List<Map<?, ?>> fills = (List<Map<?, ?>>) response.getBody();
        assertThat(fills).isNotEmpty();
        assertThat(fills).allMatch(f -> "BTC-KRW".equals(f.get("market")));
    }

    @Test
    void 사용자_fill_조회_orderId_필터() {
        String buyerToken = signupAndLogin("query006c-buyer@example.com");
        String sellerToken = signupAndLogin("query006c-seller@example.com");
        depositKrw("query006c-buyer@example.com", new BigDecimal("20000"));
        depositBtc("query006c-seller@example.com", new BigDecimal("0.001"));

        var buy1 = createOrder(buyerToken, "BTC-KRW", "BUY", "100000000", "0.0001");
        Long buyOrderId = ((Number) buy1.getBody().get("orderId")).longValue();
        createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");

        createOrder(buyerToken, "BTC-KRW", "BUY", "100000000", "0.0001");
        createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");

        var response = getFills(buyerToken, "BTC-KRW", buyOrderId);
        List<Map<?, ?>> fills = (List<Map<?, ?>>) response.getBody();

        assertThat(fills).hasSize(1);
        assertThat(((Number) fills.get(0).get("orderId")).longValue()).isEqualTo(buyOrderId);
    }

    @Test
    void 체결_없는_사용자_fill_조회_빈_결과() {
        String token = signupAndLogin("query007@example.com");
        var response = getFills(token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody()).isEmpty();
    }

    @Test
    void fill_조회_토큰_없음() {
        var response = restTemplate.getForEntity("/api/v1/fills", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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

    private ResponseEntity<Map> createOrder(String token, String market, String side, String price, String quantity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var body = Map.of(
                "market", market, "side", side, "type", "LIMIT",
                "timeInForce", "GTC", "price", price, "quantity", quantity
        );
        return restTemplate.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<List> getFills(String token, String market) {
        return getFills(token, market, null);
    }

    private ResponseEntity<List> getFills(String token, String market, Long orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        StringBuilder url = new StringBuilder("/api/v1/fills");
        String separator = "?";
        if (market != null) {
            url.append(separator).append("market=").append(market);
            separator = "&";
        }
        if (orderId != null) {
            url.append(separator).append("orderId=").append(orderId);
        }
        return restTemplate.exchange(url.toString(), HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }
}
