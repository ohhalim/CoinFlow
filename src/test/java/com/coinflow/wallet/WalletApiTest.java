package com.coinflow.wallet;

import com.coinflow.auth.repository.UserRepository;
import com.coinflow.event.repository.DomainEventRepository;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.matching.MatchingEngine;
import com.coinflow.order.repository.OrderRepository;
import com.coinflow.support.IntegrityAssertions;
import com.coinflow.support.TestcontainersConfig;
import com.coinflow.trade.repository.TradeRepository;
import com.coinflow.wallet.domain.LedgerType;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.domain.WalletLedger;
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

@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class WalletApiTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private MarketRepository marketRepository;
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

    // ── WAL-001 지갑 조회 ─────────────────────────────────────────────

    @Test
    void 지갑_조회_성공() {
        String token = signupAndLogin("wallet001@example.com");

        var response = getWallets(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> wallets = (List<Map<?, ?>>) response.getBody();
        assertThat(wallets).isNotEmpty();
        assertThat(wallets).allMatch(w ->
                w.containsKey("walletId") &&
                w.containsKey("asset") &&
                w.containsKey("availableBalance") &&
                w.containsKey("lockedBalance")
        );
    }

    @Test
    void 지갑_조회_토큰_없음() {
        var response = restTemplate.getForEntity("/api/v1/wallets", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── WAL-002 원장 조회 ─────────────────────────────────────────────

    @Test
    void 원장_조회_성공() {
        String token = signupAndLogin("wallet002@example.com");
        depositKrw("wallet002@example.com", new BigDecimal("10000000"));
        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        var response = getLedgers(token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> ledgers = (List<Map<?, ?>>) response.getBody();
        assertThat(ledgers).isNotEmpty();
        assertThat(ledgers).allMatch(l ->
                l.containsKey("ledgerId") &&
                l.containsKey("type") &&
                l.containsKey("deltaAvailable") &&
                l.containsKey("availableBalanceAfter")
        );
    }

    @Test
    void 원장_조회_asset_필터() {
        String token = signupAndLogin("wallet003@example.com");
        depositKrw("wallet003@example.com", new BigDecimal("10000000"));
        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        var response = getLedgers(token, "KRW");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> ledgers = (List<Map<?, ?>>) response.getBody();
        assertThat(ledgers).isNotEmpty();
        assertThat(ledgers).allMatch(l -> "KRW".equals(l.get("asset")));
    }

    @Test
    void 원장_조회_limit_적용() {
        String token = signupAndLogin("wallet003b@example.com");
        depositKrw("wallet003b@example.com", new BigDecimal("10000000"));

        var createResponse = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();
        cancelOrder(token, orderId);
        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        var response = getLedgers(token, "KRW", 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody()).hasSize(2);
    }


    // ── LED-001 원장 기록 검증 ────────────────────────────────────────

    @Test
    void 주문_생성_시_ORDER_LOCK_원장_기록() {
        String token = signupAndLogin("wallet004@example.com");
        depositKrw("wallet004@example.com", new BigDecimal("10000000"));

        createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);

        var user = userRepository.findByEmail("wallet004@example.com").orElseThrow();
        List<WalletLedger> ledgers = walletLedgerRepository.findAllByUserIdAndAssetOrderByCreatedAtDesc(user.getId(), "KRW");

        assertThat(ledgers).hasSize(1);
        WalletLedger ledger = ledgers.get(0);
        assertThat(ledger.getType()).isEqualTo(LedgerType.ORDER_LOCK);
        assertThat(ledger.getDeltaAvailable()).isEqualByComparingTo("-10000");
        assertThat(ledger.getDeltaLocked()).isEqualByComparingTo("10000");
        assertThat(ledger.getLockedBalanceAfter()).isEqualByComparingTo("10000");
        assertThat(ledger.getOrderId()).isNotNull();
        assertThat(ledger.getTradeId()).isNull();
    }

    @Test
    void 주문_취소_시_ORDER_CANCEL_RELEASE_원장_기록() {
        String token = signupAndLogin("wallet005@example.com");
        depositKrw("wallet005@example.com", new BigDecimal("10000000"));

        var createResponse = createOrder(token, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();
        cancelOrder(token, orderId);

        var user = userRepository.findByEmail("wallet005@example.com").orElseThrow();
        List<WalletLedger> ledgers = walletLedgerRepository.findAllByUserIdAndAssetOrderByCreatedAtDesc(user.getId(), "KRW");

        assertThat(ledgers).hasSize(2);
        WalletLedger cancelLedger = ledgers.get(0); // 최신순이므로 첫 번째가 취소
        assertThat(cancelLedger.getType()).isEqualTo(LedgerType.ORDER_CANCEL_RELEASE);
        assertThat(cancelLedger.getDeltaAvailable()).isEqualByComparingTo("10000");
        assertThat(cancelLedger.getDeltaLocked()).isEqualByComparingTo("-10000");
        assertThat(cancelLedger.getAvailableBalanceAfter()).isEqualByComparingTo("10000000");
        assertThat(cancelLedger.getLockedBalanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    void 체결_시_정산_원장_4종_기록() {
        String buyerToken = signupAndLogin("wallet006a@example.com");
        String sellerToken = signupAndLogin("wallet006b@example.com");
        depositKrw("wallet006a@example.com", new BigDecimal("10000000"));
        depositBtc("wallet006b@example.com", new BigDecimal("0.001"));

        createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

        var buyer = userRepository.findByEmail("wallet006a@example.com").orElseThrow();
        var seller = userRepository.findByEmail("wallet006b@example.com").orElseThrow();

        // 매수자: KRW ORDER_LOCK + TRADE_BUY_QUOTE_SETTLE, BTC TRADE_BUY_BASE_CREDIT
        List<WalletLedger> buyerKrwLedgers = walletLedgerRepository
                .findAllByUserIdAndAssetOrderByCreatedAtDesc(buyer.getId(), "KRW");
        List<WalletLedger> buyerBtcLedgers = walletLedgerRepository
                .findAllByUserIdAndAssetOrderByCreatedAtDesc(buyer.getId(), "BTC");

        assertThat(buyerKrwLedgers).hasSize(2);
        assertThat(buyerKrwLedgers.get(0).getType()).isEqualTo(LedgerType.TRADE_BUY_QUOTE_SETTLE);
        assertThat(buyerKrwLedgers.get(1).getType()).isEqualTo(LedgerType.ORDER_LOCK);

        assertThat(buyerBtcLedgers).hasSize(1);
        assertThat(buyerBtcLedgers.get(0).getType()).isEqualTo(LedgerType.TRADE_BUY_BASE_CREDIT);
        assertThat(buyerBtcLedgers.get(0).getDeltaAvailable()).isEqualByComparingTo("0.0001");

        // 매도자: BTC ORDER_LOCK + TRADE_SELL_BASE_SETTLE, KRW TRADE_SELL_QUOTE_CREDIT
        List<WalletLedger> sellerBtcLedgers = walletLedgerRepository
                .findAllByUserIdAndAssetOrderByCreatedAtDesc(seller.getId(), "BTC");
        List<WalletLedger> sellerKrwLedgers = walletLedgerRepository
                .findAllByUserIdAndAssetOrderByCreatedAtDesc(seller.getId(), "KRW");

        assertThat(sellerBtcLedgers).hasSize(2);
        assertThat(sellerBtcLedgers.get(0).getType()).isEqualTo(LedgerType.TRADE_SELL_BASE_SETTLE);

        assertThat(sellerKrwLedgers).hasSize(1);
        assertThat(sellerKrwLedgers.get(0).getType()).isEqualTo(LedgerType.TRADE_SELL_QUOTE_CREDIT);
        assertThat(sellerKrwLedgers.get(0).getDeltaAvailable()).isEqualByComparingTo("10000");
    }

    // ── WAL-003 입금 API ──────────────────────────────────────────────

    @Test
    void KRW_입금_성공() {
        String token = signupAndLogin("wallet007@example.com");

        var response = depositViaApi(token, "KRW", "1000000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("asset")).isEqualTo("KRW");
        assertThat(new BigDecimal((String) response.getBody().get("availableBalance")))
                .isEqualByComparingTo("1000000");
    }

    @Test
    void BTC_입금_성공() {
        String token = signupAndLogin("wallet008@example.com");

        var response = depositViaApi(token, "BTC", "0.5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("asset")).isEqualTo("BTC");
        assertThat(new BigDecimal((String) response.getBody().get("availableBalance")))
                .isEqualByComparingTo("0.5");
    }

    @Test
    void 입금_후_SEED_DEPOSIT_원장_기록() {
        String token = signupAndLogin("wallet009@example.com");
        depositViaApi(token, "KRW", "500000");

        var user = userRepository.findByEmail("wallet009@example.com").orElseThrow();
        List<WalletLedger> ledgers = walletLedgerRepository
                .findAllByUserIdAndAssetOrderByCreatedAtDesc(user.getId(), "KRW");

        assertThat(ledgers).hasSize(1);
        assertThat(ledgers.get(0).getType()).isEqualTo(LedgerType.SEED_DEPOSIT);
        assertThat(ledgers.get(0).getDeltaAvailable()).isEqualByComparingTo("500000");
        assertThat(ledgers.get(0).getAvailableBalanceAfter()).isEqualByComparingTo("500000");
    }

    @Test
    void 존재하지_않는_자산_입금_실패() {
        String token = signupAndLogin("wallet010@example.com");

        var response = depositViaApi(token, "ETH", "1.0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    void 입금액_0이하_실패() {
        String token = signupAndLogin("wallet011@example.com");

        var response = depositViaApi(token, "KRW", "0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void 입금_토큰_없음() {
        var response = restTemplate.postForEntity("/api/v1/wallets/deposit",
                Map.of("asset", "KRW", "amount", "1000"), Map.class);
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

    private ResponseEntity<Map> depositViaApi(String token, String asset, String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/wallets/deposit", HttpMethod.POST,
                new HttpEntity<>(Map.of("asset", asset, "amount", amount), headers), Map.class);
    }

    private ResponseEntity<List> getWallets(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/wallets", HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }

    private ResponseEntity<List> getLedgers(String token, String asset) {
        return getLedgers(token, asset, null);
    }

    private ResponseEntity<List> getLedgers(String token, String asset, Integer limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        StringBuilder url = new StringBuilder("/api/v1/wallets/ledgers");
        String separator = "?";
        if (asset != null) {
            url.append(separator).append("asset=").append(asset);
            separator = "&";
        }
        if (limit != null) {
            url.append(separator).append("limit=").append(limit);
        }
        return restTemplate.exchange(url.toString(), HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }
}
