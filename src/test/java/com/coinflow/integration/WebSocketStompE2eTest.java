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
import com.coinflow.websocket.dto.TradeFeedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "coinflow.websocket.trade-feed.enabled=true"
)
@EmbeddedKafka(
        partitions = 4,
        topics = {"coinflow.order.events", "coinflow.trade.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import(TestcontainersConfig.class)
class WebSocketStompE2eTest {

    @LocalServerPort private int port;

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
    void 실제_STOMP_client가_체결_feed_topic에서_메시지를_수신한다() throws Exception {
        BlockingQueue<TradeFeedMessage> receivedMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> transportErrors = new LinkedBlockingQueue<>();
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompSession session = stompClient
                .connectAsync(webSocketUrl(), new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession session, StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable exception) {
                        transportErrors.add(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        transportErrors.add(exception);
                    }
                })
                .get(5, TimeUnit.SECONDS);

        try {
            session.subscribe("/topic/trades/BTC-KRW", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return TradeFeedMessage.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedMessages.add((TradeFeedMessage) payload);
                }
            });

            String buyerToken = signupAndLogin("ws-e2e-buyer@example.com");
            String sellerToken = signupAndLogin("ws-e2e-seller@example.com");
            depositKrw("ws-e2e-buyer@example.com", new BigDecimal("10000000"));
            depositBtc("ws-e2e-seller@example.com", new BigDecimal("0.001"));

            createOrder(buyerToken, "BTC-KRW", "BUY", "LIMIT", "GTC", "100000000", "0.0001", null);
            createOrder(sellerToken, "BTC-KRW", "SELL", "LIMIT", "GTC", "100000000", "0.0001", null);

            outboxPublisher.publishPendingEvents();

            TradeFeedMessage message = receivedMessages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            assertThat(message.eventId()).isNotNull();
            assertThat(message.market()).isEqualTo("BTC-KRW");
            assertThat(message.price()).isEqualTo("100000000");
            assertThat(message.quantity()).isEqualTo("0.0001");
            assertThat(message.side()).isEqualTo("SELL");
            assertThat(message.tradedAt()).isNotBlank();
            assertThat(transportErrors).isEmpty();
        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
            stompClient.stop();
        }
    }

    private String webSocketUrl() {
        return "ws://localhost:%d/ws".formatted(port);
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
