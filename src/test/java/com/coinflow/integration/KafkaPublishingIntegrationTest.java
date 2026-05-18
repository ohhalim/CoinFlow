package com.coinflow.integration;

import com.coinflow.auth.repository.UserRepository;
import com.coinflow.event.domain.DomainEventType;
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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 4,
        topics = {"coinflow.order.events", "coinflow.trade.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import(TestcontainersConfig.class)
class KafkaPublishingIntegrationTest {

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
    @Autowired private EmbeddedKafkaBroker embeddedKafkaBroker;

    private Consumer<String, String> tradeConsumer;

    @BeforeEach
    void setUp() {
        domainEventRepository.deleteAllInBatch();
        walletLedgerRepository.deleteAllInBatch();
        tradeRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        matchingEngine.clearAll();

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "outbox-test-" + UUID.randomUUID(),
                "false",
                embeddedKafkaBroker
        );
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        tradeConsumer = new DefaultKafkaConsumerFactory<>(
                consumerProps,
                new StringDeserializer(),
                new StringDeserializer()
        ).createConsumer();
        tradeConsumer.subscribe(List.of("coinflow.trade.events"));
    }

    @AfterEach
    void tearDown() {
        if (tradeConsumer != null) {
            tradeConsumer.close();
        }
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
    void 주문_체결_후_domain_events를_Kafka로_발행하고_published_처리한다() {
        String buyerToken = signupAndLogin("kafka001-buyer@example.com");
        String sellerToken = signupAndLogin("kafka001-seller@example.com");
        depositKrw("kafka001-buyer@example.com", new BigDecimal("10000000"));
        depositBtc("kafka001-seller@example.com", new BigDecimal("0.001"));

        createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
        createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

        assertThat(domainEventRepository.findAllByPublishedFalseOrderByIdAsc()).isNotEmpty();

        int publishedCount = outboxPublisher.publishPendingEvents();

        assertThat(publishedCount).isGreaterThan(0);
        assertThat(domainEventRepository.findAllByPublishedFalseOrderByIdAsc()).isEmpty();
        assertThat(domainEventRepository.findAllByEventType(DomainEventType.TRADE_CREATED))
                .allSatisfy(event -> {
                    assertThat(event.isPublished()).isTrue();
                    assertThat(event.getPublishedAt()).isNotNull();
                    assertThat(event.getPublishAttempts()).isZero();
                });

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(tradeConsumer, Duration.ofSeconds(5));
        List<ConsumerRecord<String, String>> tradeRecords = StreamSupport
                .stream(records.records("coinflow.trade.events").spliterator(), false)
                .toList();

        assertThat(tradeRecords)
                .extracting(ConsumerRecord::key)
                .contains("BTC-KRW");
        assertThat(tradeRecords)
                .extracting(ConsumerRecord::value)
                .anySatisfy(value -> assertThat(value)
                        .contains("\"eventType\":\"TRADE_CREATED\"")
                        .contains("\"marketSymbol\":\"BTC-KRW\"")
                        .contains("\"payload\""));
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
}
