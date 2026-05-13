package com.coinflow.order;

import com.coinflow.auth.repository.UserRepository;
import com.coinflow.support.TestcontainersConfig;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class OrderApiTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;

    // ── ORDER-001 주문 생성 ───────────────────────────────────────────

    @Test
    void BUY_주문_생성_성공() {
        String token = signupAndLogin("order001@example.com");
        depositKrw("order001@example.com", new BigDecimal("10000000"));

        var response = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys("orderId", "market", "side", "price", "status");
        assertThat(response.getBody().get("status")).isEqualTo("OPEN");
        assertThat(response.getBody().get("side")).isEqualTo("BUY");

        var user = userRepository.findByEmail("order001@example.com").orElseThrow();
        var krwWallet = findWallet(user.getId(), "KRW");
        assertThat(krwWallet.getLockedBalance()).isEqualByComparingTo("10000");
        assertThat(krwWallet.getAvailableBalance()).isEqualByComparingTo("9990000");
    }

    @Test
    void SELL_주문_생성_성공() {
        String token = signupAndLogin("order002@example.com");
        depositBtc("order002@example.com", new BigDecimal("0.001"));

        var response = createOrder(token, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("OPEN");
        assertThat(response.getBody().get("side")).isEqualTo("SELL");

        var user = userRepository.findByEmail("order002@example.com").orElseThrow();
        var btcWallet = findWallet(user.getId(), "BTC");
        assertThat(btcWallet.getLockedBalance()).isEqualByComparingTo("0.0001");
    }

    @Test
    void 주문_생성_잔고_부족() {
        String token = signupAndLogin("order003@example.com");

        var response = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void 주문_생성_없는_마켓() {
        String token = signupAndLogin("order004@example.com");

        var response = createOrder(token, "ETH-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("MARKET_NOT_FOUND");
    }

    @Test
    void 주문_생성_틱사이즈_오류() {
        String token = signupAndLogin("order005@example.com");
        depositKrw("order005@example.com", new BigDecimal("10000000"));

        var response = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000.5", "0.0001", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_TICK_SIZE");
    }

    @Test
    void 주문_생성_clientOrderId_중복() {
        String token = signupAndLogin("order006@example.com");
        depositKrw("order006@example.com", new BigDecimal("100000000"));

        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", "my-order-1");
        var response = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", "my-order-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("DUPLICATE_CLIENT_ORDER_ID");
    }

    @Test
    void 주문_생성_토큰_없음() {
        var response = restTemplate.postForEntity("/api/v1/orders", Map.of(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── ORDER-002 주문 취소 ───────────────────────────────────────────

    @Test
    void 주문_취소_성공() {
        String token = signupAndLogin("order007@example.com");
        depositKrw("order007@example.com", new BigDecimal("10000000"));

        var createResponse = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();

        var cancelResponse = cancelOrder(token, orderId);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().get("status")).isEqualTo("CANCELED");
        assertThat(cancelResponse.getBody().get("releasedAsset")).isEqualTo("KRW");

        var user = userRepository.findByEmail("order007@example.com").orElseThrow();
        var krwWallet = findWallet(user.getId(), "KRW");
        assertThat(krwWallet.getLockedBalance()).isEqualByComparingTo("0");
        assertThat(krwWallet.getAvailableBalance()).isEqualByComparingTo("10000000");
    }

    @Test
    void 이미_취소된_주문_재취소() {
        String token = signupAndLogin("order008@example.com");
        depositKrw("order008@example.com", new BigDecimal("10000000"));

        var createResponse = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();

        cancelOrder(token, orderId);
        var response = cancelOrder(token, orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("ORDER_NOT_CANCELABLE");
    }

    @Test
    void 다른_유저_주문_취소_시도() {
        String token1 = signupAndLogin("order009a@example.com");
        String token2 = signupAndLogin("order009b@example.com");
        depositKrw("order009a@example.com", new BigDecimal("10000000"));

        var createResponse = createOrder(token1, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();

        var response = cancelOrder(token2, orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("ORDER_NOT_FOUND");
    }

    // ── ORDER-003 주문 조회 ───────────────────────────────────────────

    @Test
    void 주문_단건_조회_성공() {
        String token = signupAndLogin("order010@example.com");
        depositKrw("order010@example.com", new BigDecimal("10000000"));

        var createResponse = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();

        var response = getOrder(token, orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("orderId", "market", "side", "price", "status", "closedAt");
        assertThat(response.getBody().get("orderId")).isEqualTo(orderId.intValue());
    }

    @Test
    void 주문_목록_조회_성공() {
        String token = signupAndLogin("order011@example.com");
        depositKrw("order011@example.com", new BigDecimal("100000000"));

        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        var response = getOrders(token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void 주문_목록_마켓_필터_조회() {
        String token = signupAndLogin("order012@example.com");
        depositKrw("order012@example.com", new BigDecimal("10000000"));

        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        var response = getOrders(token, "BTC-KRW");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) response.getBody()).allMatch(o -> {
            var order = (Map<?, ?>) o;
            return "BTC-KRW".equals(order.get("market"));
        });
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

    private Wallet findWallet(Long userId, String asset) {
        return walletRepository.findAllByUserId(userId).stream()
                .filter(w -> w.getAsset().equals(asset))
                .findFirst().orElseThrow();
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

    private ResponseEntity<Map> getOrder(String token, Long orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/orders/" + orderId, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<java.util.List> getOrders(String token, String market) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String url = market != null ? "/api/v1/orders?market=" + market : "/api/v1/orders";
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), java.util.List.class);
    }
}
