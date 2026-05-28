# CoinFlow Phase 2 구현 플랜 및 완료 상태

## Phase 1 완료 상태 — 이미 있는 것

| 항목 | 파일 | 비고 |
|------|------|------|
| `domain_events` 테이블 | `V3__create_orders_...sql` | `published`, `published_at`, `publish_attempts` 컬럼 포함 |
| `DomainEvent` 엔티티 | `event/domain/DomainEvent.java` | Outbox 필드 완비 |
| `DomainEventRecorder` | `event/service/DomainEventRecorder.java` | 6종 이벤트 DB 저장 |
| `Dockerfile` | 루트 | 멀티스테이지 빌드 |

당시 없는 것: `docker-compose.yml`, Kafka 의존성, WebSocket 의존성, OutboxPublisher, WebSocketBroadcaster

## Phase 2 완료 상태

초기 계획은 `#19~#22` 단위로 작성했지만, 실제 구현은 Phase 1 안정화 이후 아래 이슈들로 나누어 완료했다.

| 실제 이슈 | 브랜치 | 완료 범위 | 검증 |
|---|---|---|---|
| `#36` | `feat/36/outbox-publisher` | Kafka 설정, Outbox Publisher, 발행 성공/실패 상태 관리 | `OutboxPublisherTest`, `KafkaPublishingIntegrationTest` |
| `#38` | `feat/38/websocket-trade-feed` | Kafka Consumer 기반 WebSocket 체결 feed | WebSocket unit test, Embedded Kafka integration test |
| `#40` | `feat/40/websocket-stomp-e2e` | 실제 STOMP client 수신 E2E | `WebSocketStompE2eTest` |
| `#42` | `feat/42/websocket-orderbook` | Kafka 주문 이벤트 기반 오더북 snapshot broadcast | `OrderBookBroadcasterTest`, `WebSocketOrderBookBroadcastIntegrationTest` |

최종 회귀 검증은 `./gradlew test` 기준 `150`개 테스트 통과로 기록했다. 자세한 실행 결과는 [TEST_RESULTS.md](../TEST_RESULTS.md)를 기준으로 한다.

### Phase 2 완료 범위

- `domain_events.published=false` 이벤트를 Kafka로 발행한다.
- 발행 성공 시 `published=true`, 실패 시 `publish_attempts++`로 상태를 남긴다.
- 주문 이벤트는 `coinflow.order.events`, 체결/정산 이벤트는 `coinflow.trade.events`로 라우팅한다.
- Kafka `TRADE_CREATED` 이벤트를 `/topic/trades/{market}`로 broadcast한다.
- 실제 STOMP client가 `/topic/trades/BTC-KRW` 메시지를 수신하는 경로를 검증했다.
- Kafka 주문 이벤트를 받아 현재 인메모리 오더북 snapshot을 `/topic/orderbook/{market}`로 broadcast한다.

### Phase 2 제외 및 후속 범위

- WebSocket 연결 인증/권한 분리
- 클라이언트 재연결/중복 수신 처리
- WebSocket/Kafka 실시간 전파 부하 테스트 완료
- 단일 market 주문 생성 병목 분리 완료
- 비동기 주문 접수 API 설계
- 주문 접수 transaction과 market worker 체결/정산 처리 분리
- delta orderbook streaming, sequence number, checksum
- WebSocket consumer 별도 서비스 분리
- DB에 결과를 쓰는 Consumer의 `processed_events` 기반 idempotency
- 일별 거래 정산 Batch

### Phase 2 이후 설계 범위

단일 market `100 order/s` 부하 기준:

- market별 command queue 적용 후 `market_lock_wait` 주요 병목 제외
- 잔여 병목: `command_queue_wait`, 단일 market worker 처리량 한계

후속 설계 기준:

| 항목 | 방향 |
|---|---|
| 주문 접수 응답 | 체결/정산 완료 대기와 분리 |
| 체결/정산 처리 | market worker에서 순차 처리 |
| 상태 조회 | 주문 상태 API와 WebSocket 이벤트 기준 |
| 정합성 | 자산 잠금, 주문 상태 전이, 원장 기록 기준 유지 |
| 상세 문서 | [Async Order Acceptance](../design/ASYNC_ORDER_ACCEPTANCE.md) |

---

## 계획 단위 1 — Kafka 환경 구성

### 목표

`docker compose up -d` 한 번으로 MySQL + Kafka가 올라오고,
Spring Boot 실행 시 Topic이 자동 생성되는 것까지 확인한다.

### 왜 KRaft인가

기존 Kafka는 메타데이터 관리를 위해 Zookeeper가 필요했다.
KRaft(Kafka Raft)는 Kafka 자체에 메타데이터를 내장해 Zookeeper 없이 동작한다.
로컬 개발 환경에서 컨테이너 하나로 끝낼 수 있다.

### 작업 목록

**1. docker-compose.yml** (신규)

```yaml
services:
  mysql:
    image: mysql:8.0
    ports: ["3306:3306"]
    environment:
      MYSQL_DATABASE: coinflow
      MYSQL_USER: coinflow
      MYSQL_PASSWORD: coinflow
      MYSQL_ROOT_PASSWORD: root
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  kafka:
    image: bitnami/kafka:3.7
    ports: ["9092:9092"]
    environment:
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "false"
    healthcheck:
      test: ["CMD", "kafka-topics.sh", "--list", "--bootstrap-server", "localhost:9092"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  mysql-data:
```

`AUTO_CREATE_TOPICS_ENABLE=false`로 설정하는 이유:
오타로 잘못된 Topic 이름이 자동 생성되는 것을 막기 위해 명시적 Topic 생성만 허용한다.

**2. build.gradle**

```groovy
implementation 'org.springframework.kafka:spring-kafka'
testImplementation 'org.springframework.kafka:spring-kafka-test'
```

**3. application.properties 추가**

```properties
# Kafka
spring.kafka.bootstrap-servers=${KAFKA_SERVERS:localhost:9092}
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.consumer.group-id=coinflow-websocket
spring.kafka.consumer.auto-offset-reset=latest
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer

# Scheduling
spring.task.scheduling.pool.size=2
```

**4. KafkaTopicConfig.java** (신규, `com.coinflow.config`)

```java
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name("coinflow.order.events")
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic tradeEventsTopic() {
        return TopicBuilder.name("coinflow.trade.events")
                .partitions(4)
                .replicas(1)
                .build();
    }
}
```

### 변경 파일 요약

| 파일 | 작업 |
|------|------|
| `docker-compose.yml` | 신규 |
| `src/main/java/com/coinflow/config/KafkaTopicConfig.java` | 신규 |
| `build.gradle` | `spring-kafka`, `spring-kafka-test` 추가 |
| `src/main/resources/application.properties` | Kafka 연결 설정 추가 |

### 완료 기준

```bash
docker compose up -d
./gradlew bootRun

# Topic 생성 확인
docker exec coinflow-kafka-1 kafka-topics.sh \
  --list --bootstrap-server localhost:9092
# 출력: coinflow.order.events, coinflow.trade.events
```

---

## 계획 단위 2 — Outbox Publisher

### 목표

`domain_events.published=false` 이벤트를 1초마다 폴링해 Kafka로 발행한다.
발행 성공 시 `published=true`, 실패 시 `publish_attempts++`.
5회 실패한 이벤트는 자동 polling 대상에서 제외한다.

### 핵심 설계 결정

| 항목 | 결정 | 이유 |
|------|------|------|
| send 방식 | `kafkaTemplate.send(...).get()` (동기) | 비동기 send는 ACK 전에 메서드가 끝나 실패를 감지 못할 수 있음 |
| 스케줄링 방식 | `fixedDelay` | `fixedRate`는 이전 실행이 끝나지 않아도 다음 실행이 시작 → 중복 처리 위험 |
| 배치 크기 | 최대 100건 | 단일 폴링 주기 내 처리량 상한. DB 부하 통제 |
| MAX_ATTEMPTS | 5 | 일시적 Kafka 장애는 5회 안에 해소된다고 가정 |
| 파티션 키 | marketSymbol | 같은 시장 이벤트 → 같은 파티션 → Consumer 순서 보장 |
| 트랜잭션 범위 | 배치 단위 `@Transactional` | 이벤트 하나 실패해도 나머지는 published 처리 |

### 작업 목록

**1. DomainEvent.java — 상태 변경 메서드 추가**

```java
public void markPublished() {
    this.published = true;
    this.publishedAt = LocalDateTime.now();
}

public void incrementAttempts() {
    this.publishAttempts++;
}
```

**2. DomainEventRepository.java — Outbox 쿼리 추가**

```java
// published=false이고 attempts < MAX_ATTEMPTS인 이벤트를 id 오름차순으로 최대 100건 조회
// id 오름차순 = 오래된 이벤트부터 발행 (FIFO 보장)
List<DomainEvent> findTop100ByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(int maxAttempts);
```

**3. OutboxPublisher.java** (신규, `com.coinflow.event.service`)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    static final int MAX_ATTEMPTS = 5;
    static final String ORDER_TOPIC = "coinflow.order.events";
    static final String TRADE_TOPIC = "coinflow.trade.events";

    private final DomainEventRepository domainEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publish() {
        List<DomainEvent> pending = domainEventRepository
                .findTop100ByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(MAX_ATTEMPTS);

        for (DomainEvent event : pending) {
            try {
                String topic = resolveTopic(event.getEventType());
                String message = buildMessage(event);
                kafkaTemplate.send(topic, event.getMarketSymbol(), message).get();
                event.markPublished();
            } catch (Exception e) {
                log.warn("Kafka publish failed. eventId={}, attempts={}, error={}",
                        event.getId(), event.getPublishAttempts(), e.getMessage());
                event.incrementAttempts();
            }
        }
    }

    private String resolveTopic(DomainEventType type) {
        return switch (type) {
            case ORDER_ACCEPTED, ORDER_PARTIALLY_FILLED,
                 ORDER_FILLED, ORDER_CANCELED        -> ORDER_TOPIC;
            case TRADE_CREATED, SETTLEMENT_COMPLETED -> TRADE_TOPIC;
        };
    }

    private String buildMessage(DomainEvent event) throws JsonProcessingException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("eventId",      event.getId());
        msg.put("eventType",    event.getEventType().name());
        msg.put("marketSymbol", event.getMarketSymbol());
        msg.put("payload",      event.getPayload());
        return objectMapper.writeValueAsString(msg);
    }
}
```

**4. CoinflowApplication.java**

```java
@EnableScheduling  // 추가
@SpringBootApplication
public class CoinflowApplication { ... }
```

### Kafka 메시지 포맷

```json
{
  "eventId":      1,
  "eventType":    "TRADE_CREATED",
  "marketSymbol": "BTC-KRW",
  "payload":      "{\"tradeId\":1,\"price\":100000000,\"quantity\":0.0001,\"quoteAmount\":10000}"
}
```

`payload`는 `DomainEventRecorder`가 직렬화한 JSON 문자열을 그대로 포함한다.
Consumer는 `payload`를 역직렬화해서 개별 필드를 사용한다.

### Dead-letter 모니터링

```sql
SELECT id, event_type, market_symbol, created_at, publish_attempts
FROM domain_events
WHERE published = false AND publish_attempts >= 5
ORDER BY created_at;
```

### 변경 파일 요약

| 파일 | 작업 |
|------|------|
| `event/domain/DomainEvent.java` | `markPublished()`, `incrementAttempts()` 추가 |
| `event/repository/DomainEventRepository.java` | Outbox 폴링 쿼리 추가 |
| `event/service/OutboxPublisher.java` | 신규 |
| `CoinflowApplication.java` | `@EnableScheduling` 추가 |

### 완료 기준

```bash
docker exec coinflow-kafka-1 kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic coinflow.trade.events \
  --from-beginning

# 주문 체결 후 1~2초 내 아래 메시지 출력 확인
{"eventId":1,"eventType":"TRADE_CREATED","marketSymbol":"BTC-KRW","payload":"{...}"}
```

---

## 계획 단위 3 — WebSocket 실시간 broadcast

아래 코드는 초기 설계 스케치다. 실제 구현은 다음 파일을 기준으로 한다.

| 실제 구현 파일 | 역할 |
|---|---|
| `config/WebSocketConfig.java` | STOMP endpoint `/ws`, simple broker `/topic` 설정 |
| `websocket/TradeFeedBroadcaster.java` | Kafka `TRADE_CREATED` 이벤트를 `/topic/trades/{market}`로 broadcast |
| `websocket/TradeFeedMessageMapper.java` | Kafka message payload를 체결 feed 메시지로 변환 |
| `websocket/OrderBookBroadcaster.java` | Kafka 주문 이벤트를 `/topic/orderbook/{market}` snapshot으로 broadcast |
| `websocket/dto/KafkaEventMessage.java` | Kafka 이벤트 공통 메시지 DTO |
| `websocket/dto/TradeFeedMessage.java` | 체결 feed 메시지 DTO |
| `websocket/dto/OrderBookSnapshotMessage.java` | 오더북 snapshot 메시지 DTO |

초기 스케치와 달리 실제 구현에서는 체결 feed와 오더북 snapshot을 분리했고, 오더북 메시지 필드는 `bids`, `asks`를 사용한다.

### 목표

Kafka Consumer가 이벤트를 수신하면 STOMP WebSocket으로 클라이언트에 push한다.
체결 발생 시 체결 피드, 주문 변경 시 오더북 스냅샷을 broadcast한다.

### 핵심 설계 결정

| 항목 | 결정 | 이유 |
|------|------|------|
| 프로토콜 | STOMP over WebSocket | pub/sub 구조가 명확. 채널 단위 구독 가능 |
| SockJS | 포함 | WebSocket 미지원 환경 폴백 자동 처리 |
| 오더북 broadcast | 전체 스냅샷 | delta 방식은 클라이언트가 로컬 상태를 유지해야 해서 패킷 유실 시 불일치 발생 |
| Consumer groupId | `coinflow-websocket` | 향후 다른 Consumer group 추가 시 독립적으로 오프셋 관리 |
| 오더북 스냅샷 소스 | MatchingEngine (in-memory) | 이미 최신 상태 유지. DB 조회 불필요 |

### 작업 목록

**1. build.gradle**

```groovy
implementation 'org.springframework.boot:spring-boot-starter-websocket'
```

**2. WebSocketConfig.java** (신규, `com.coinflow.config`)

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        registry.addEndpoint("/ws")   // raw WebSocket (wscat 등 테스트 클라이언트용)
                .setAllowedOriginPatterns("*");
    }
}
```

**3. KafkaMessage.java** (신규, `com.coinflow.websocket.dto`)

Kafka에서 수신한 메시지를 역직렬화하는 내부 DTO.

```java
public record KafkaMessage(
        Long   eventId,
        String eventType,
        String marketSymbol,
        String payload        // TRADE_CREATED 등 이벤트별 JSON 문자열
) {}
```

**4. TradeMessage.java** (신규, `/topic/trades/{market}` 발행용)

```java
public record TradeMessage(
        String market,
        String price,
        String quantity,
        String side,       // "BUY" or "SELL" — taker 기준
        String tradedAt
) {}
```

**5. OrderBookSnapshotMessage.java / PriceLevel.java** (신규, `/topic/orderbook/{market}` 발행용)

```java
public record OrderBookSnapshotMessage(
        Long             eventId,
        String           market,
        List<PriceLevel> bids,    // 높은 가격 우선
        List<PriceLevel> asks     // 낮은 가격 우선
) {}

public record PriceLevel(String price, String quantity) {}
```

**6. MatchingEngine.java — 오더북 조회 메서드 추가**

WebSocketBroadcaster가 현재 오더북 상태를 읽기 위해 필요.

```java
// MatchingEngine에 추가
public List<OrderBookEntry> getBuySide(String marketSymbol) {
    MemoryOrderBook book = books.get(marketSymbol);
    return book == null ? List.of() : book.getBuySide();
}

public List<OrderBookEntry> getSellSide(String marketSymbol) {
    MemoryOrderBook book = books.get(marketSymbol);
    return book == null ? List.of() : book.getSellSide();
}
```

**7. WebSocketBroadcaster.java** (신규, `com.coinflow.websocket`)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketBroadcaster {

    private final SimpMessagingTemplate messaging;
    private final MatchingEngine        matchingEngine;
    private final ObjectMapper          objectMapper;

    // ── 체결 이벤트 수신 ────────────────────────────────────────────────

    @KafkaListener(topics = "coinflow.trade.events", groupId = "coinflow-websocket")
    public void onTradeEvent(String raw) {
        try {
            KafkaMessage msg = parse(raw);
            if ("TRADE_CREATED".equals(msg.eventType())) {
                TradeMessage trade = buildTradeMessage(msg);
                messaging.convertAndSend("/topic/trades/" + msg.marketSymbol(), trade);
            }
        } catch (Exception e) {
            log.error("Failed to broadcast trade event. raw={}", raw, e);
        }
    }

    // ── 주문 이벤트 수신 ────────────────────────────────────────────────

    private static final Set<String> ORDER_BOOK_TRIGGERS = Set.of(
            "ORDER_ACCEPTED", "ORDER_FILLED", "ORDER_PARTIALLY_FILLED", "ORDER_CANCELED"
    );

    @KafkaListener(topics = "coinflow.order.events", groupId = "coinflow-websocket")
    public void onOrderEvent(String raw) {
        try {
            KafkaMessage msg = parse(raw);
            if (ORDER_BOOK_TRIGGERS.contains(msg.eventType())) {
                OrderBookSnapshotMessage snapshot = buildSnapshot(msg.eventId(), msg.marketSymbol());
                messaging.convertAndSend("/topic/orderbook/" + msg.marketSymbol(), snapshot);
            }
        } catch (Exception e) {
            log.error("Failed to broadcast order event. raw={}", raw, e);
        }
    }

    // ── 내부 메서드 ─────────────────────────────────────────────────────

    private KafkaMessage parse(String raw) throws JsonProcessingException {
        return objectMapper.readValue(raw, KafkaMessage.class);
    }

    private TradeMessage buildTradeMessage(KafkaMessage msg) throws JsonProcessingException {
        JsonNode p = objectMapper.readTree(msg.payload());

        // taker가 buyOrderId와 같으면 BUY, sellOrderId와 같으면 SELL
        long takerOrderId = p.get("takerOrderId").asLong();
        long buyOrderId   = p.get("buyOrderId").asLong();
        String side = (takerOrderId == buyOrderId) ? "BUY" : "SELL";

        return new TradeMessage(
                msg.marketSymbol(),
                p.get("price").decimalValue().toPlainString(),
                p.get("quantity").decimalValue().toPlainString(),
                side,
                LocalDateTime.now().toString()
        );
    }

    private OrderBookSnapshotMessage buildSnapshot(Long eventId, String marketSymbol) {
        List<PriceLevel> bids = aggregateLevels(matchingEngine.getBuySide(marketSymbol));
        List<PriceLevel> asks = aggregateLevels(matchingEngine.getSellSide(marketSymbol));
        return new OrderBookSnapshotMessage(eventId, marketSymbol, bids, asks);
    }

    // 같은 가격의 여러 주문을 하나의 호가 레벨로 합산한다
    private List<PriceLevel> aggregateLevels(List<OrderBookEntry> entries) {
        Map<BigDecimal, BigDecimal> byPrice = new LinkedHashMap<>();
        for (OrderBookEntry e : entries) {
            byPrice.merge(e.price(), e.remainingQuantity(), BigDecimal::add);
        }
        return byPrice.entrySet().stream()
                .map(en -> new PriceLevel(
                        en.getKey().toPlainString(),
                        en.getValue().stripTrailingZeros().toPlainString()
                ))
                .toList();
    }
}
```

**8. SecurityConfig.java 수정**

```java
.requestMatchers("/ws/**").permitAll()
```

### 변경 파일 요약

| 파일 | 작업 |
|------|------|
| `config/WebSocketConfig.java` | 신규 |
| `websocket/WebSocketBroadcaster.java` | 신규 |
| `websocket/dto/KafkaMessage.java` | 신규 |
| `websocket/dto/TradeMessage.java` | 신규 |
| `websocket/dto/OrderBookSnapshotMessage.java` | 신규 |
| `websocket/dto/PriceLevel.java` | 신규 |
| `order/matching/MatchingEngine.java` | `getBuySide()`, `getSellSide()` 추가 |
| `build.gradle` | `spring-boot-starter-websocket` 추가 |
| `config/SecurityConfig.java` | `/ws/**` permitAll 추가 |

실제 완료 구현에서는 `WebSocketBroadcaster` 단일 클래스 대신 `TradeFeedBroadcaster`와 `OrderBookBroadcaster`로 책임을 분리했다.

### 완료 기준

```bash
# wscat 설치: npm install -g wscat
wscat -c ws://localhost:8080/ws

# STOMP CONNECT 후 구독 (STOMP 프레임 직접 입력)
CONNECT
accept-version:1.2

^@

SUBSCRIBE
id:sub-0
destination:/topic/trades/BTC-KRW

^@

# 별도 터미널에서 매수/매도 주문 체결 후 아래 메시지 수신 확인
MESSAGE
destination:/topic/trades/BTC-KRW

{"market":"BTC-KRW","price":"100000000","quantity":"0.0001","side":"BUY","tradedAt":"..."}
```

---

## 계획 단위 4 — E2E 통합 테스트

### 목표

주문 체결 → Kafka 발행 → WebSocket 수신 전 구간을 자동으로 검증한다.
CI에서 Docker 없이 실행되어야 한다 → MySQL은 Testcontainer, Kafka는 `@EmbeddedKafka`.

### EmbeddedKafka + Testcontainer 조합

```
MySQL      → Testcontainer (TestcontainersConfig, 이미 구현)
Kafka      → @EmbeddedKafka (in-process, Docker 불필요)
```

`@EmbeddedKafka`의 `bootstrapServersProperty` 옵션을 사용하면
`spring.kafka.bootstrap-servers`를 임베디드 브로커 주소로 자동 오버라이드한다.

```java
@EmbeddedKafka(
    partitions = 1,
    topics = {"coinflow.order.events", "coinflow.trade.events"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"  // 자동 오버라이드
)
```

### 테스트 시나리오

| ID | 시나리오 | 검증 항목 |
|----|----------|-----------|
| EVT-E2E-001 | 주문 체결 → `TRADE_CREATED` Kafka 발행 | eventType, marketSymbol 포함 여부 |
| EVT-E2E-002 | 주문 취소 → `ORDER_CANCELED` Kafka 발행 | eventType 포함 여부 |
| EVT-E2E-003 | Kafka 발행 실패 → `publish_attempts` 1 증가 | attempts == 1, published == false |
| EVT-E2E-004 | `publish_attempts >= 5` → 폴링 제외 | 6번째 poll에서 해당 이벤트 미발행 |
| WS-E2E-001 | 주문 체결 → `/topic/trades/BTC-KRW` 수신 | market, price, quantity 일치, 5초 내 수신 |

### 작업 목록

**1. KafkaPublishingTest.java** (신규, `com.coinflow.e2e`)

```java
@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {"coinflow.order.events", "coinflow.trade.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class KafkaPublishingTest {

    @Autowired private TestRestTemplate     restTemplate;
    @Autowired private OutboxPublisher      outboxPublisher;
    @Autowired private UserRepository       userRepository;
    @Autowired private WalletRepository     walletRepository;
    @Autowired private MatchingEngine       matchingEngine;

    @Value("${spring.embedded.kafka.brokers}")
    private String brokers;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        matchingEngine.clearAll();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,    brokers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,             "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,    "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("coinflow.trade.events", "coinflow.order.events"));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    // EVT-E2E-001
    @Test
    void 주문_체결_시_TRADE_CREATED_Kafka_발행() {
        String buyerToken  = signupAndLogin("kafka001a@example.com");
        String sellerToken = signupAndLogin("kafka001b@example.com");
        depositKrw("kafka001a@example.com", new BigDecimal("10000000"));
        depositBtc("kafka001b@example.com", new BigDecimal("0.001"));
        createOrder(buyerToken,  "BTC-KRW", "BUY",  "100000000", "0.0001");
        createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");

        outboxPublisher.publish();

        ConsumerRecords<String, String> records =
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

        List<String> tradeMessages = StreamSupport.stream(records.spliterator(), false)
                .filter(r -> "coinflow.trade.events".equals(r.topic()))
                .map(ConsumerRecord::value)
                .filter(v -> v.contains("TRADE_CREATED"))
                .toList();

        assertThat(tradeMessages).isNotEmpty();
        assertThat(tradeMessages.get(0)).contains("\"marketSymbol\":\"BTC-KRW\"");
    }

    // EVT-E2E-002
    @Test
    void 주문_취소_시_ORDER_CANCELED_Kafka_발행() {
        String token = signupAndLogin("kafka002@example.com");
        depositKrw("kafka002@example.com", new BigDecimal("10000000"));
        var resp = createOrder(token, "BTC-KRW", "BUY", "100000000", "0.0001");
        Long orderId = ((Number) resp.getBody().get("orderId")).longValue();
        cancelOrder(token, orderId);

        outboxPublisher.publish();

        ConsumerRecords<String, String> records =
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

        boolean hasCanceled = StreamSupport.stream(records.spliterator(), false)
                .filter(r -> "coinflow.order.events".equals(r.topic()))
                .map(ConsumerRecord::value)
                .anyMatch(v -> v.contains("ORDER_CANCELED"));

        assertThat(hasCanceled).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String signupAndLogin(String email) {
        restTemplate.postForEntity("/api/v1/auth/signup",
                Map.of("email", email, "password", "password1234", "nickname", "tester"), Map.class);
        var login = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", "password1234"), Map.class);
        return (String) login.getBody().get("accessToken");
    }

    private void depositKrw(String email, BigDecimal amount) { deposit(email, "KRW", amount); }
    private void depositBtc(String email, BigDecimal amount) { deposit(email, "BTC", amount); }

    private void deposit(String email, String asset, BigDecimal amount) {
        var user   = userRepository.findByEmail(email).orElseThrow();
        var wallet = walletRepository.findAllByUserId(user.getId()).stream()
                .filter(w -> w.getAsset().equals(asset)).findFirst().orElseThrow();
        wallet.deposit(amount);
        walletRepository.save(wallet);
    }

    private ResponseEntity<Map> createOrder(String token, String market, String side,
                                             String price, String quantity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var body = Map.of("market", market, "side", side, "type", "LIMIT",
                          "timeInForce", "GTC", "price", price, "quantity", quantity);
        return restTemplate.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    private void cancelOrder(String token, Long orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange("/api/v1/orders/" + orderId + "/cancel",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }
}
```

**2. OutboxRetryTest.java** (신규, `com.coinflow.e2e`)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {"coinflow.order.events", "coinflow.trade.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class OutboxRetryTest {

    @Autowired private TestRestTemplate        restTemplate;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private DomainEventRepository   domainEventRepository;
    @Autowired private UserRepository          userRepository;
    @Autowired private WalletRepository        walletRepository;
    @Autowired private MatchingEngine          matchingEngine;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;   // Kafka send 실패 시뮬레이션

    @BeforeEach
    void setUp() {
        matchingEngine.clearAll();
        when(kafkaTemplate.send(any(), any(), any()))
                .thenThrow(new RuntimeException("Kafka unavailable"));
    }

    // EVT-E2E-003
    @Test
    void Kafka_발행_실패_시_publish_attempts_증가() {
        String token = signupAndLogin("retry001@example.com");
        depositKrw("retry001@example.com", new BigDecimal("10000000"));
        createOrder(token, "BTC-KRW", "BUY", "100000000", "0.0001");

        DomainEvent before = domainEventRepository.findAll().stream()
                .filter(e -> !e.isPublished()).findFirst().orElseThrow();

        outboxPublisher.publish();

        DomainEvent after = domainEventRepository.findById(before.getId()).orElseThrow();
        assertThat(after.isPublished()).isFalse();
        assertThat(after.getPublishAttempts()).isEqualTo(1);
    }

    // EVT-E2E-004
    @Test
    void publish_attempts_MAX_초과_시_폴링_제외() {
        String token = signupAndLogin("retry002@example.com");
        depositKrw("retry002@example.com", new BigDecimal("10000000"));
        createOrder(token, "BTC-KRW", "BUY", "100000000", "0.0001");

        // MAX_ATTEMPTS번 실패시키기
        for (int i = 0; i < OutboxPublisher.MAX_ATTEMPTS; i++) {
            outboxPublisher.publish();
        }

        long countBefore = domainEventRepository.findAll().stream()
                .filter(e -> !e.isPublished()).count();

        // MAX_ATTEMPTS+1번째 publish — 해당 이벤트는 폴링 대상에서 제외되어야 함
        reset(kafkaTemplate);  // mock 초기화 (이제 send가 성공해도 호출 자체가 안 돼야 함)
        outboxPublisher.publish();

        long countAfter = domainEventRepository.findAll().stream()
                .filter(e -> !e.isPublished()).count();

        // 폴링 제외 → published가 변하지 않음
        assertThat(countAfter).isEqualTo(countBefore);
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    // ── helpers (KafkaPublishingTest와 동일) ────────────────────────────
    private String signupAndLogin(String email) { /* ... */ return null; }
    private void depositKrw(String email, BigDecimal amount) { /* ... */ }
    private ResponseEntity<Map> createOrder(String token, String market, String side,
                                             String price, String quantity) { return null; }
}
```

**3. WebSocketBroadcastTest.java** (신규, `com.coinflow.e2e`)

```java
@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {"coinflow.order.events", "coinflow.trade.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class WebSocketBroadcastTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private OutboxPublisher  outboxPublisher;
    @Autowired private UserRepository   userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private MatchingEngine   matchingEngine;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        matchingEngine.clearAll();
    }

    // WS-E2E-001
    @Test
    void 주문_체결_시_WebSocket_TradeMessage_수신() throws Exception {
        // STOMP 클라이언트 연결 및 구독
        CompletableFuture<Map> received = new CompletableFuture<>();

        WebSocketStompClient stompClient = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())))
        );
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompSession session = stompClient
                .connect("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        session.subscribe("/topic/trades/BTC-KRW", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return Map.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete((Map) payload);
            }
        });

        // 주문 체결 후 OutboxPublisher 트리거
        String buyerToken  = signupAndLogin("ws001a@example.com");
        String sellerToken = signupAndLogin("ws001b@example.com");
        depositKrw("ws001a@example.com", new BigDecimal("10000000"));
        depositBtc("ws001b@example.com", new BigDecimal("0.001"));
        createOrder(buyerToken,  "BTC-KRW", "BUY",  "100000000", "0.0001");
        createOrder(sellerToken, "BTC-KRW", "SELL", "100000000", "0.0001");
        outboxPublisher.publish();

        // 5초 내 TradeMessage 수신 확인
        Map msg = received.get(5, TimeUnit.SECONDS);
        assertThat(msg.get("market")).isEqualTo("BTC-KRW");
        assertThat(msg.get("price")).isEqualTo("100000000");
        assertThat(msg.get("quantity")).isEqualTo("0.0001");
        assertThat(msg.get("side")).isIn("BUY", "SELL");

        session.disconnect();
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private String signupAndLogin(String email) {
        restTemplate.postForEntity("/api/v1/auth/signup",
                Map.of("email", email, "password", "password1234", "nickname", "tester"), Map.class);
        var login = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", "password1234"), Map.class);
        return (String) login.getBody().get("accessToken");
    }

    private void depositKrw(String email, BigDecimal amount) { deposit(email, "KRW", amount); }
    private void depositBtc(String email, BigDecimal amount) { deposit(email, "BTC", amount); }

    private void deposit(String email, String asset, BigDecimal amount) {
        var user   = userRepository.findByEmail(email).orElseThrow();
        var wallet = walletRepository.findAllByUserId(user.getId()).stream()
                .filter(w -> w.getAsset().equals(asset)).findFirst().orElseThrow();
        wallet.deposit(amount);
        walletRepository.save(wallet);
    }

    private void createOrder(String token, String market, String side,
                              String price, String quantity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var body = Map.of("market", market, "side", side, "type", "LIMIT",
                          "timeInForce", "GTC", "price", price, "quantity", quantity);
        restTemplate.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }
}
```

### 변경 파일 요약

| 파일 | 작업 |
|------|------|
| `integration/KafkaPublishingIntegrationTest.java` | 신규 |
| `event/service/OutboxPublisherTest.java` | 신규 |
| `integration/WebSocketTradeFeedIntegrationTest.java` | 신규 |
| `integration/WebSocketStompE2eTest.java` | 신규 |
| `integration/WebSocketOrderBookBroadcastIntegrationTest.java` | 신규 |

---

## 실제 구현 순서

```
#36 feat/36/outbox-publisher
  └─ Kafka/Outbox 설정 + OutboxPublisher
  └─ 검증: OutboxPublisherTest, KafkaPublishingIntegrationTest
    ↓
#38 feat/38/websocket-trade-feed
  └─ WebSocketConfig + TradeFeedBroadcaster + TradeFeedMessageMapper
  └─ 검증: Kafka Consumer → SimpMessagingTemplate broadcast
    ↓
#40 feat/40/websocket-stomp-e2e
  └─ 실제 STOMP client 연결/구독/수신 검증
  └─ 검증: WebSocketStompE2eTest
    ↓
#42 feat/42/websocket-orderbook
  └─ Kafka 주문 이벤트 → 오더북 snapshot broadcast
  └─ 검증: WebSocketOrderBookBroadcastIntegrationTest
    ↓
전체 회귀 테스트
  └─ 검증: ./gradlew test, 150 tests passed
```

---

## 면접 Q&A 준비

| 예상 질문 | 답변 포인트 |
|-----------|------------|
| 왜 Kafka를 썼나? | 체결 이벤트 하나를 WebSocket, 통계, 리스크 등 여러 소비자가 독립적으로 처리해야 해서. OrderService가 소비자를 알 필요 없음 |
| Outbox Pattern이 뭔가? | DB 트랜잭션과 Kafka 발행은 하나로 묶을 수 없음. 이벤트를 DB에 먼저 저장(Phase 1)하고 별도 스케줄러가 폴링해서 발행 → 이벤트 유실 0 |
| 왜 동기 send인가? | `.get()`으로 Kafka ACK 확인 후 `published=true`. 비동기면 Kafka 실패를 감지 못하고 이벤트가 유실될 수 있음 |
| at-least-once 중복은 어떻게? | WebSocket broadcast는 중복 수신이 UX에만 영향. DB Consumer 추가 시 `processed_events` 테이블로 `event.id` 기준 dedup |
| 파티션 키를 왜 marketSymbol로? | 같은 시장 이벤트가 항상 같은 파티션 → Consumer가 시장 단위 순서 보장. BTC-KRW와 ETH-KRW는 다른 파티션에서 병렬 처리 |
| 오더북을 왜 스냅샷으로? | delta는 클라이언트가 로컬 상태를 유지해야 하고 패킷 유실 시 불일치. 스냅샷은 항상 서버 현재 상태를 보장. Phase 3에서 delta + sequence로 최적화 예정 |
| polling의 한계는? | 최대 1초 딜레이. Debezium CDC로 전환하면 MySQL binlog 기반 실시간 감지, 수십 ms로 감소 |
| 테스트는 어떻게 했나? | MySQL은 Testcontainer, Kafka는 EmbeddedKafka. Docker 없이 CI에서 전 구간 자동 검증 |
