package com.coinflow.integration;

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
import com.coinflow.wallet.repository.WalletLedgerRepository;
import com.coinflow.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class ConcurrencyIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 20;
    private static final int EXPECTED_SUCCESS_ORDERS = 10;
    private static final int TAKER_REQUESTS = 10;
    private static final int ORDERBOOK_WRITERS = 2;
    private static final int ORDERBOOK_READERS = 3;
    private static final int ORDERBOOK_WRITES_PER_WRITER = 10;
    private static final int ORDERBOOK_READS_PER_READER = 20;

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

    @RepeatedTest(10)
    void CON_001_동일_사용자_동시_BUY_주문은_잔고를_초과해_성공하지_않는다() throws Exception {
        String email = "con001-buyer@example.com";
        String token = signupAndLogin(email);
        depositKrw(email, new BigDecimal("100000"));

        List<ResponseEntity<Map>> responses = runConcurrently(CONCURRENT_REQUESTS, index ->
                createOrder(
                        token,
                        "BTC-KRW",
                        "BUY",
                        "LIMIT",
                        "GTC",
                        "100000000",
                        "0.0001",
                        "con001-" + index
                )
        );

        long successCount = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                .count();
        long insufficientBalanceCount = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.BAD_REQUEST)
                .filter(response -> "INSUFFICIENT_BALANCE".equals(response.getBody().get("code")))
                .count();

        assertThat(successCount).isEqualTo(EXPECTED_SUCCESS_ORDERS);
        assertThat(insufficientBalanceCount).isEqualTo(CONCURRENT_REQUESTS - EXPECTED_SUCCESS_ORDERS);
        assertThat(orderRepository.count()).isEqualTo(successCount);

        var user = userRepository.findByEmail(email).orElseThrow();
        Wallet krwWallet = findWallet(user.getId(), "KRW");

        assertThat(krwWallet.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(krwWallet.getLockedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(krwWallet.getAvailableBalance().add(krwWallet.getLockedBalance()))
                .isEqualByComparingTo("100000");
        assertThat(krwWallet.getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(krwWallet.getLockedBalance()).isEqualByComparingTo("100000");

        long orderLockLedgerCount = walletLedgerRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(ledger -> ledger.getType() == LedgerType.ORDER_LOCK)
                .count();
        assertThat(orderLockLedgerCount).isEqualTo(successCount);
    }

    @RepeatedTest(10)
    void CON_002_하나의_maker_주문은_동시_taker_체결로_수량을_초과하지_않는다() throws Exception {
        String sellerEmail = "con002-seller@example.com";
        String sellerToken = signupAndLogin(sellerEmail);
        depositBtc(sellerEmail, new BigDecimal("0.5"));

        var makerResponse = createOrder(
                sellerToken,
                "BTC-KRW",
                "SELL",
                "LIMIT",
                "GTC",
                "100000",
                "0.5",
                "con002-maker"
        );
        assertThat(makerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long makerOrderId = ((Number) makerResponse.getBody().get("orderId")).longValue();

        List<String> buyerTokens = new ArrayList<>();
        for (int i = 0; i < TAKER_REQUESTS; i++) {
            String buyerEmail = "con002-buyer-" + i + "@example.com";
            buyerTokens.add(signupAndLogin(buyerEmail));
            depositKrw(buyerEmail, new BigDecimal("10000"));
        }

        List<ResponseEntity<Map>> responses = runConcurrently(TAKER_REQUESTS, index ->
                createOrder(
                        buyerTokens.get(index),
                        "BTC-KRW",
                        "BUY",
                        "LIMIT",
                        "GTC",
                        "100000",
                        "0.1",
                        "con002-taker-" + index
                )
        );

        assertThat(responses)
                .extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.CREATED);

        long filledTakerCount = responses.stream()
                .filter(response -> "FILLED".equals(response.getBody().get("status")))
                .count();
        long openTakerCount = responses.stream()
                .filter(response -> "OPEN".equals(response.getBody().get("status")))
                .count();

        assertThat(filledTakerCount).isEqualTo(5);
        assertThat(openTakerCount).isEqualTo(5);
        assertThat(tradeRepository.count()).isEqualTo(5);

        BigDecimal totalTradedQuantity = tradeRepository.findAll().stream()
                .map(trade -> trade.getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalTradedQuantity).isEqualByComparingTo("0.5");

        var makerOrder = orderRepository.findById(makerOrderId).orElseThrow();
        assertThat(makerOrder.getStatus().name()).isEqualTo("FILLED");
        assertThat(makerOrder.getExecutedQuantity()).isEqualByComparingTo("0.5");
        assertThat(makerOrder.getRemainingQuantity()).isEqualByComparingTo("0");
        assertThat(makerOrder.getLockedAmount()).isEqualByComparingTo("0");

        var seller = userRepository.findByEmail(sellerEmail).orElseThrow();
        Wallet sellerBtc = findWallet(seller.getId(), "BTC");
        Wallet sellerKrw = findWallet(seller.getId(), "KRW");

        assertThat(sellerBtc.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(sellerBtc.getLockedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(sellerBtc.getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(sellerBtc.getLockedBalance()).isEqualByComparingTo("0");
        assertThat(sellerKrw.getAvailableBalance()).isEqualByComparingTo("50000");
    }

    @RepeatedTest(10)
    void CON_003_주문_처리_중_오더북_반복_조회는_안정적으로_응답한다() throws Exception {
        List<String> writerTokens = new ArrayList<>();
        for (int i = 0; i < ORDERBOOK_WRITERS; i++) {
            String writerEmail = "con003-writer-" + i + "@example.com";
            writerTokens.add(signupAndLogin(writerEmail));
            depositKrw(writerEmail, new BigDecimal("1000000"));
        }

        int taskCount = ORDERBOOK_WRITERS + ORDERBOOK_READERS;
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<List<ResponseEntity<Map>>>> tasks = new ArrayList<>();

        for (int writerIndex = 0; writerIndex < ORDERBOOK_WRITERS; writerIndex++) {
            int index = writerIndex;
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();

                List<ResponseEntity<Map>> responses = new ArrayList<>();
                for (int orderIndex = 0; orderIndex < ORDERBOOK_WRITES_PER_WRITER; orderIndex++) {
                    responses.add(createOrder(
                            writerTokens.get(index),
                            "BTC-KRW",
                            "BUY",
                            "LIMIT",
                            "GTC",
                            "100000000",
                            "0.0001",
                            "con003-writer-" + index + "-" + orderIndex
                    ));
                }
                return responses;
            });
        }

        for (int readerIndex = 0; readerIndex < ORDERBOOK_READERS; readerIndex++) {
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();

                List<ResponseEntity<Map>> responses = new ArrayList<>();
                for (int readIndex = 0; readIndex < ORDERBOOK_READS_PER_READER; readIndex++) {
                    responses.add(getOrderBook());
                }
                return responses;
            });
        }

        try {
            List<Future<List<ResponseEntity<Map>>>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ResponseEntity<Map>> responses = new ArrayList<>();
            for (Future<List<ResponseEntity<Map>>> future : futures) {
                responses.addAll(future.get(20, TimeUnit.SECONDS));
            }

            List<ResponseEntity<Map>> writeResponses = responses.stream()
                    .filter(response -> response.getBody() != null && response.getBody().containsKey("orderId"))
                    .toList();
            List<ResponseEntity<Map>> readResponses = responses.stream()
                    .filter(response -> response.getBody() != null && response.getBody().containsKey("bids"))
                    .toList();

            assertThat(writeResponses)
                    .hasSize(ORDERBOOK_WRITERS * ORDERBOOK_WRITES_PER_WRITER)
                    .extracting(ResponseEntity::getStatusCode)
                    .containsOnly(HttpStatus.CREATED);
            assertThat(readResponses)
                    .hasSize(ORDERBOOK_READERS * ORDERBOOK_READS_PER_READER)
                    .extracting(ResponseEntity::getStatusCode)
                    .containsOnly(HttpStatus.OK);

            for (ResponseEntity<Map> response : readResponses) {
                assertThat(response.getBody()).containsKeys("market", "bids", "asks");
                assertThat(response.getBody().get("market")).isEqualTo("BTC-KRW");
                assertThat(response.getBody().get("bids")).isInstanceOf(List.class);
                assertThat(response.getBody().get("asks")).isInstanceOf(List.class);
            }

            assertThat(orderRepository.count()).isEqualTo(ORDERBOOK_WRITERS * ORDERBOOK_WRITES_PER_WRITER);
            assertThat(matchingEngine.getBuySide("BTC-KRW")).hasSize(ORDERBOOK_WRITERS * ORDERBOOK_WRITES_PER_WRITER);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    @RepeatedTest(10)
    void CON_004_주문_취소와_체결_경합은_최종_상태와_잔고가_일관된다() throws Exception {
        String buyerEmail = "con004-buyer@example.com";
        String buyerToken = signupAndLogin(buyerEmail);
        depositKrw(buyerEmail, new BigDecimal("50000"));

        String sellerEmail = "con004-seller@example.com";
        String sellerToken = signupAndLogin(sellerEmail);
        depositBtc(sellerEmail, new BigDecimal("0.5"));

        var makerResponse = createOrder(
                buyerToken,
                "BTC-KRW",
                "BUY",
                "LIMIT",
                "GTC",
                "100000",
                "0.5",
                "con004-maker"
        );
        assertThat(makerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long makerOrderId = ((Number) makerResponse.getBody().get("orderId")).longValue();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<ResponseEntity<Map>> cancelFuture = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return cancelOrder(buyerToken, makerOrderId);
            });
            Future<ResponseEntity<Map>> takerFuture = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return createOrder(
                        sellerToken,
                        "BTC-KRW",
                        "SELL",
                        "LIMIT",
                        "GTC",
                        "100000",
                        "0.5",
                        "con004-taker"
                );
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ResponseEntity<Map> cancelResponse = cancelFuture.get(10, TimeUnit.SECONDS);
            ResponseEntity<Map> takerResponse = takerFuture.get(10, TimeUnit.SECONDS);

            var makerOrder = orderRepository.findById(makerOrderId).orElseThrow();
            assertThat(makerOrder.getStatus().name()).isIn("CANCELED", "FILLED");
            assertThat(makerOrder.getLockedAmount()).isEqualByComparingTo("0");
            assertThat(makerOrder.getExecutedQuantity().add(makerOrder.getRemainingQuantity()))
                    .isEqualByComparingTo(makerOrder.getOriginalQuantity());

            var buyer = userRepository.findByEmail(buyerEmail).orElseThrow();
            var seller = userRepository.findByEmail(sellerEmail).orElseThrow();
            Wallet buyerKrw = findWallet(buyer.getId(), "KRW");
            Wallet buyerBtc = findWallet(buyer.getId(), "BTC");
            Wallet sellerKrw = findWallet(seller.getId(), "KRW");
            Wallet sellerBtc = findWallet(seller.getId(), "BTC");

            assertWalletNeverNegative(buyerKrw);
            assertWalletNeverNegative(buyerBtc);
            assertWalletNeverNegative(sellerKrw);
            assertWalletNeverNegative(sellerBtc);

            if ("CANCELED".equals(makerOrder.getStatus().name())) {
                assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(cancelResponse.getBody().get("status")).isEqualTo("CANCELED");
                assertThat(takerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(takerResponse.getBody().get("status")).isEqualTo("OPEN");
                assertThat(tradeRepository.count()).isZero();

                assertThat(buyerKrw.getAvailableBalance()).isEqualByComparingTo("50000");
                assertThat(buyerKrw.getLockedBalance()).isEqualByComparingTo("0");
                assertThat(sellerBtc.getAvailableBalance()).isEqualByComparingTo("0");
                assertThat(sellerBtc.getLockedBalance()).isEqualByComparingTo("0.5");
                assertThat(matchingEngine.getBuySide("BTC-KRW")).isEmpty();
                assertThat(matchingEngine.getSellSide("BTC-KRW")).hasSize(1);
            } else {
                assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(cancelResponse.getBody().get("code")).isEqualTo("ORDER_NOT_CANCELABLE");
                assertThat(takerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(takerResponse.getBody().get("status")).isEqualTo("FILLED");
                assertThat(tradeRepository.count()).isEqualTo(1);

                BigDecimal totalTradedQuantity = tradeRepository.findAll().stream()
                        .map(trade -> trade.getQuantity())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(totalTradedQuantity).isEqualByComparingTo("0.5");
                assertThat(buyerKrw.getAvailableBalance()).isEqualByComparingTo("0");
                assertThat(buyerKrw.getLockedBalance()).isEqualByComparingTo("0");
                assertThat(buyerBtc.getAvailableBalance()).isEqualByComparingTo("0.5");
                assertThat(sellerKrw.getAvailableBalance()).isEqualByComparingTo("50000");
                assertThat(sellerBtc.getAvailableBalance()).isEqualByComparingTo("0");
                assertThat(sellerBtc.getLockedBalance()).isEqualByComparingTo("0");
                assertThat(matchingEngine.getBuySide("BTC-KRW")).isEmpty();
                assertThat(matchingEngine.getSellSide("BTC-KRW")).isEmpty();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    @RepeatedTest(10)
    void CON_005_동일_clientOrderId_동시_주문은_하나만_성공한다() throws Exception {
        String email = "con005-buyer@example.com";
        String token = signupAndLogin(email);
        depositKrw(email, new BigDecimal("1000000"));

        List<ResponseEntity<Map>> responses = runConcurrently(10, index ->
                createOrder(
                        token,
                        "BTC-KRW",
                        "BUY",
                        "LIMIT",
                        "GTC",
                        "100000000",
                        "0.0001",
                        "con005-duplicate"
                )
        );

        long successCount = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                .count();
        long duplicateCount = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .filter(response -> "DUPLICATE_CLIENT_ORDER_ID".equals(response.getBody().get("code")))
                .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(9);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(tradeRepository.count()).isZero();

        var user = userRepository.findByEmail(email).orElseThrow();
        Wallet krwWallet = findWallet(user.getId(), "KRW");
        assertThat(krwWallet.getAvailableBalance()).isEqualByComparingTo("990000");
        assertThat(krwWallet.getLockedBalance()).isEqualByComparingTo("10000");

        long orderLockLedgerCount = walletLedgerRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(ledger -> ledger.getType() == LedgerType.ORDER_LOCK)
                .count();
        assertThat(orderLockLedgerCount).isEqualTo(1);
        assertThat(matchingEngine.getBuySide("BTC-KRW")).hasSize(1);
    }

    @RepeatedTest(10)
    void CON_006_동일_주문_동시_취소는_한번만_잔고를_해제한다() throws Exception {
        String email = "con006-buyer@example.com";
        String token = signupAndLogin(email);
        depositKrw(email, new BigDecimal("100000"));

        var createResponse = createOrder(
                token,
                "BTC-KRW",
                "BUY",
                "LIMIT",
                "GTC",
                "100000000",
                "0.0001",
                "con006-maker"
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long orderId = ((Number) createResponse.getBody().get("orderId")).longValue();

        List<ResponseEntity<Map>> responses = runConcurrently(10, index -> cancelOrder(token, orderId));

        long successCount = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.OK)
                .filter(response -> "CANCELED".equals(response.getBody().get("status")))
                .count();
        long notCancelableCount = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.BAD_REQUEST)
                .filter(response -> "ORDER_NOT_CANCELABLE".equals(response.getBody().get("code")))
                .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(notCancelableCount).isEqualTo(9);

        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus().name()).isEqualTo("CANCELED");
        assertThat(order.getLockedAmount()).isEqualByComparingTo("0");

        var user = userRepository.findByEmail(email).orElseThrow();
        Wallet krwWallet = findWallet(user.getId(), "KRW");
        assertThat(krwWallet.getAvailableBalance()).isEqualByComparingTo("100000");
        assertThat(krwWallet.getLockedBalance()).isEqualByComparingTo("0");

        long cancelLedgerCount = walletLedgerRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(ledger -> ledger.getType() == LedgerType.ORDER_CANCEL_RELEASE)
                .count();
        assertThat(cancelLedgerCount).isEqualTo(1);
        assertThat(matchingEngine.getBuySide("BTC-KRW")).isEmpty();
    }

    private List<ResponseEntity<Map>> runConcurrently(
            int taskCount,
            ConcurrentOrderTask task
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            int index = i;
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return task.execute(index);
            });
        }

        try {
            List<Future<ResponseEntity<Map>>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ResponseEntity<Map>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
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

    private Wallet findWallet(Long userId, String asset) {
        return walletRepository.findAllByUserId(userId).stream()
                .filter(wallet -> wallet.getAsset().equals(asset))
                .findFirst()
                .orElseThrow();
    }

    private void depositKrw(String email, BigDecimal amount) {
        var user = userRepository.findByEmail(email).orElseThrow();
        Wallet wallet = findWallet(user.getId(), "KRW");
        wallet.deposit(amount);
        walletRepository.save(wallet);
    }

    private void depositBtc(String email, BigDecimal amount) {
        var user = userRepository.findByEmail(email).orElseThrow();
        Wallet wallet = findWallet(user.getId(), "BTC");
        wallet.deposit(amount);
        walletRepository.save(wallet);
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
        Map<String, String> body = new HashMap<>();
        body.put("market", market);
        body.put("side", side);
        body.put("type", type);
        body.put("timeInForce", timeInForce);
        body.put("price", price);
        body.put("quantity", quantity);
        body.put("clientOrderId", clientOrderId);
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

    private ResponseEntity<Map> getOrderBook() {
        return restTemplate.exchange(
                "/api/v1/markets/BTC-KRW/orderbook?depth=100",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Map.class
        );
    }

    private void assertWalletNeverNegative(Wallet wallet) {
        assertThat(wallet.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(wallet.getLockedBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @FunctionalInterface
    private interface ConcurrentOrderTask {
        ResponseEntity<Map> execute(int index);
    }
}
