package com.coinflow.integration;

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

@SuppressWarnings("rawtypes")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class MatchingSettlementTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DomainEventRepository domainEventRepository;
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

    // ── SET-001: BUY taker 가격차이 환불 ─────────────────────────────
    // 매도가(98,000,000) < 매수가(100,000,000), 0.0001 BTC 체결
    // locked=10,000 KRW, quoteAmount=9,800 KRW → 환불 200 KRW

    @Test
    void SET_001_BUY_taker_체결가_낮으면_잔여_KRW_환불() {
        String buyerToken  = signupAndLogin("set001-buyer@example.com");
        String sellerToken = signupAndLogin("set001-seller@example.com");
        depositKrw("set001-buyer@example.com",  new BigDecimal("10000"));
        depositBtc("set001-seller@example.com", new BigDecimal("0.001"));

        // maker: SELL at 98,000,000
        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "98000000", "0.0001", null);

        // taker: BUY at 100,000,000 → locks 10,000 KRW, matches at 9,800 KRW
        var buyResponse = createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(buyResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(buyResponse.getBody().get("status")).isEqualTo("FILLED");

        var buyer  = userRepository.findByEmail("set001-buyer@example.com").orElseThrow();
        var seller = userRepository.findByEmail("set001-seller@example.com").orElseThrow();

        var buyerKrw  = findWallet(buyer.getId(), "KRW");
        var buyerBtc  = findWallet(buyer.getId(), "BTC");
        var sellerKrw = findWallet(seller.getId(), "KRW");
        var sellerBtc = findWallet(seller.getId(), "BTC");

        // 환불 200 KRW 확인
        assertThat(buyerKrw.getAvailableBalance()).isEqualByComparingTo("200");
        assertThat(buyerKrw.getLockedBalance()).isEqualByComparingTo("0");
        assertThat(buyerBtc.getAvailableBalance()).isEqualByComparingTo("0.0001");

        // seller는 9,800 KRW 수령
        assertThat(sellerKrw.getAvailableBalance()).isEqualByComparingTo("9800");
        assertThat(sellerBtc.getLockedBalance()).isEqualByComparingTo("0");

        // 자산 보존 불변식: buyer 소비 KRW = seller 수령 KRW + buyer 환불 KRW
        // 10,000 = 9,800 + 200 ✅
    }

    // ── SET-001b: 부분 체결 반복 시 rounding 누적 ────────────────────
    // maker1 SELL 98,000,000 / maker2 SELL 99,000,000
    // taker BUY 100,000,000 qty=0.0002 → 두 번 체결
    // 1차 locked 차감: 20,000→10,000 released 10,000 refund 200
    // 2차 locked 차감: 10,000→0     released 10,000 refund 100
    // 최종 buyer KRW available = 300, BTC = 0.0002

    @Test
    void SET_001b_부분_체결_반복_환불_누적() {
        String buyerToken   = signupAndLogin("set001b-buyer@example.com");
        String seller1Token = signupAndLogin("set001b-seller1@example.com");
        String seller2Token = signupAndLogin("set001b-seller2@example.com");
        depositKrw("set001b-buyer@example.com",    new BigDecimal("20000"));
        depositBtc("set001b-seller1@example.com",  new BigDecimal("0.001"));
        depositBtc("set001b-seller2@example.com",  new BigDecimal("0.001"));

        // maker1: cheaper → matched first
        createOrder(seller1Token, "BTC-KRW", "SELL", "LIMIT", "GTC", "98000000", "0.0001", null);
        // maker2: slightly more expensive
        createOrder(seller2Token, "BTC-KRW", "SELL", "LIMIT", "GTC", "99000000", "0.0001", null);

        // taker BUY 0.0002 → fully filled across two makers
        var buyResponse = createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0002", null);

        assertThat(buyResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(buyResponse.getBody().get("status")).isEqualTo("FILLED");

        var trades = (List<?>) buyResponse.getBody().get("trades");
        assertThat(trades).hasSize(2);

        var buyer = userRepository.findByEmail("set001b-buyer@example.com").orElseThrow();
        var buyerKrw = findWallet(buyer.getId(), "KRW");
        var buyerBtc = findWallet(buyer.getId(), "BTC");

        // 총 지출: 9,800 + 9,900 = 19,700 → 환불: 300 KRW
        assertThat(buyerKrw.getAvailableBalance()).isEqualByComparingTo("300");
        assertThat(buyerKrw.getLockedBalance()).isEqualByComparingTo("0");
        assertThat(buyerBtc.getAvailableBalance()).isEqualByComparingTo("0.0002");
    }

    // ── SET-002: SELL taker 정산 ─────────────────────────────────────

    @Test
    void SET_002_SELL_taker_정산_정확성() {
        String buyerToken  = signupAndLogin("set002-buyer@example.com");
        String sellerToken = signupAndLogin("set002-seller@example.com");
        depositKrw("set002-buyer@example.com",  new BigDecimal("10000"));
        depositBtc("set002-seller@example.com", new BigDecimal("0.001"));

        // maker: BUY
        createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        // taker: SELL → 전량 체결
        var sellResponse = createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(sellResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sellResponse.getBody().get("status")).isEqualTo("FILLED");

        var buyer  = userRepository.findByEmail("set002-buyer@example.com").orElseThrow();
        var seller = userRepository.findByEmail("set002-seller@example.com").orElseThrow();

        var buyerKrw  = findWallet(buyer.getId(), "KRW");
        var buyerBtc  = findWallet(buyer.getId(), "BTC");
        var sellerKrw = findWallet(seller.getId(), "KRW");
        var sellerBtc = findWallet(seller.getId(), "BTC");

        // 동일 가격이라 환불 없음
        assertThat(buyerKrw.getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(buyerKrw.getLockedBalance()).isEqualByComparingTo("0");
        assertThat(buyerBtc.getAvailableBalance()).isEqualByComparingTo("0.0001");

        assertThat(sellerKrw.getAvailableBalance()).isEqualByComparingTo("10000");
        assertThat(sellerBtc.getAvailableBalance()).isEqualByComparingTo("0.0009");
        assertThat(sellerBtc.getLockedBalance()).isEqualByComparingTo("0");
    }

    // ── SET-005: Self-trade mixed candidates ─────────────────────────
    // user2 SELL seq=1 (price 99,000,000) + user1 SELL seq=2 (price 100,000,000)
    // user1 BUY at 100,000,000 → hasSelfTrade가 user1의 SELL을 탐지 → 전체 거절

    @Test
    void SET_005_호가창에_타인_주문_있어도_자기체결_후보_있으면_거절() {
        String user1Token = signupAndLogin("set005-user1@example.com");
        String user2Token = signupAndLogin("set005-user2@example.com");
        depositKrw("set005-user1@example.com",  new BigDecimal("10000000"));
        depositBtc("set005-user1@example.com",  new BigDecimal("0.001"));
        depositBtc("set005-user2@example.com",  new BigDecimal("0.001"));

        // user2 SELL at 99,000,000 (더 싸서 매칭 우선)
        createOrder(user2Token, "BTC-KRW", "SELL", "LIMIT", "GTC", "99000000", "0.0001", null);
        // user1 SELL at 100,000,000 (자기 주문)
        createOrder(user1Token, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

        // user1 BUY at 100,000,000 → user1 자신의 SELL과 가격 교차 → 거절
        var buyResponse = createOrder(user1Token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(buyResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(buyResponse.getBody().get("code")).isEqualTo("SELF_TRADE_NOT_ALLOWED");

        // user1 KRW 잔고 변동 없음 (주문 거절 → lock 없음)
        var user1 = userRepository.findByEmail("set005-user1@example.com").orElseThrow();
        var user1Krw = findWallet(user1.getId(), "KRW");
        assertThat(user1Krw.getLockedBalance()).isEqualByComparingTo("0");
        assertThat(user1Krw.getAvailableBalance()).isEqualByComparingTo("10000000");
    }

    // ── CAN-002: 부분 체결 후 취소 ───────────────────────────────────
    // BUY 0.0002 중 0.0001 체결 → PARTIALLY_FILLED
    // 취소 시 남은 locked(10,000 KRW) 반환

    @Test
    void CAN_002_부분_체결_후_취소_lockedAmount_정확() {
        String buyerToken  = signupAndLogin("can002-buyer@example.com");
        String sellerToken = signupAndLogin("can002-seller@example.com");
        depositKrw("can002-buyer@example.com",  new BigDecimal("20000"));
        depositBtc("can002-seller@example.com", new BigDecimal("0.001"));

        // maker: SELL 0.0001 at 100,000,000
        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

        // taker: BUY 0.0002 → 0.0001만 체결, 나머지 OPEN
        var buyResponse = createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0002", null);
        Long buyOrderId = ((Number) buyResponse.getBody().get("orderId")).longValue();
        assertThat(buyResponse.getBody().get("status")).isEqualTo("PARTIALLY_FILLED");

        var buyer = userRepository.findByEmail("can002-buyer@example.com").orElseThrow();
        var buyerKrw = findWallet(buyer.getId(), "KRW");
        // 체결 후: available=0 locked=10,000 (남은 0.0001 BTC 분 KRW)
        assertThat(buyerKrw.getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(buyerKrw.getLockedBalance()).isEqualByComparingTo("10000");

        // 취소
        var cancelResponse = cancelOrder(getToken("can002-buyer@example.com"), buyOrderId);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().get("status")).isEqualTo("CANCELED");

        buyerKrw = findWallet(buyer.getId(), "KRW");
        // 취소 후: locked 10,000 → available 복귀
        assertThat(buyerKrw.getAvailableBalance()).isEqualByComparingTo("10000");
        assertThat(buyerKrw.getLockedBalance()).isEqualByComparingTo("0");
        assertThat(findWallet(buyer.getId(), "BTC").getAvailableBalance()).isEqualByComparingTo("0.0001");
    }

    // ── MAT-001: 가격 우선순위 ────────────────────────────────────────
    // 3개의 SELL이 다른 가격으로 등록 → BUY는 가장 낮은 가격 SELL과 체결

    @Test
    void MAT_001_가격_우선순위_가장_낮은_SELL과_체결() {
        String buyerToken   = signupAndLogin("mat001-buyer@example.com");
        String seller1Token = signupAndLogin("mat001-seller1@example.com");
        String seller2Token = signupAndLogin("mat001-seller2@example.com");
        String seller3Token = signupAndLogin("mat001-seller3@example.com");
        depositKrw("mat001-buyer@example.com",    new BigDecimal("10000"));
        depositBtc("mat001-seller1@example.com",  new BigDecimal("0.001"));
        depositBtc("mat001-seller2@example.com",  new BigDecimal("0.001"));
        depositBtc("mat001-seller3@example.com",  new BigDecimal("0.001"));

        createOrder(seller1Token, "BTC-KRW", "SELL", "LIMIT", "GTC", "99000000",  "0.0001", null);
        createOrder(seller2Token, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);
        createOrder(seller3Token, "BTC-KRW", "SELL", "LIMIT", "GTC", "98000000",  "0.0001", null); // 최저가

        // BUY at 100,000,000 → seller3(98,000,000)과 체결
        var buyResponse = createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(buyResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(buyResponse.getBody().get("status")).isEqualTo("FILLED");

        var trades = (List<?>) buyResponse.getBody().get("trades");
        assertThat(trades).hasSize(1);
        var trade = (Map<?, ?>) trades.get(0);
        assertThat(trade.get("price")).isEqualTo("98000000"); // 최저가 체결

        // buyer: 9,800 KRW 지출, 200 환불, 0.0001 BTC 수령
        var buyer = userRepository.findByEmail("mat001-buyer@example.com").orElseThrow();
        assertThat(findWallet(buyer.getId(), "KRW").getAvailableBalance()).isEqualByComparingTo("200");
        assertThat(findWallet(buyer.getId(), "BTC").getAvailableBalance()).isEqualByComparingTo("0.0001");

        // seller3 (98,000,000): 9,800 KRW 수령
        var seller3 = userRepository.findByEmail("mat001-seller3@example.com").orElseThrow();
        assertThat(findWallet(seller3.getId(), "KRW").getAvailableBalance()).isEqualByComparingTo("9800");

        // seller1, seller2: 변동 없음 (체결 안 됨)
        var seller1 = userRepository.findByEmail("mat001-seller1@example.com").orElseThrow();
        var seller2 = userRepository.findByEmail("mat001-seller2@example.com").orElseThrow();
        assertThat(findWallet(seller1.getId(), "KRW").getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(findWallet(seller2.getId(), "KRW").getAvailableBalance()).isEqualByComparingTo("0");
    }

    // ── 불변식: wallet 잔고 음수 불가 ────────────────────────────────

    @Test
    void INVARIANT_체결_후_모든_지갑_잔고_음수_불가() {
        String buyerToken  = signupAndLogin("inv001-buyer@example.com");
        String sellerToken = signupAndLogin("inv001-seller@example.com");
        depositKrw("inv001-buyer@example.com",  new BigDecimal("98000"));
        depositBtc("inv001-seller@example.com", new BigDecimal("0.01"));

        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "98000000", "0.001", null);
        createOrder(buyerToken,  "BTC-KRW", "BUY",  "LIMIT", "GTC", "100000000", "0.001", null);

        var buyer  = userRepository.findByEmail("inv001-buyer@example.com").orElseThrow();
        var seller = userRepository.findByEmail("inv001-seller@example.com").orElseThrow();

        walletRepository.findAllByUserId(buyer.getId()).forEach(w -> {
            assertThat(w.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(w.getLockedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
        walletRepository.findAllByUserId(seller.getId()).forEach(w -> {
            assertThat(w.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(w.getLockedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────

    private final Map<String, String> tokenCache = new java.util.HashMap<>();

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
        String token = (String) loginResponse.getBody().get("accessToken");
        tokenCache.put(email, token);
        return token;
    }

    private String getToken(String email) {
        return tokenCache.get(email);
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
}
