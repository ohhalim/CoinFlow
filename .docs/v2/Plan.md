# CoinFlow Phase 2 구현 플랜

## Phase 1 완료 상태 — 이미 있는 것

| 항목 | 파일 | 비고 |
|------|------|------|
| `domain_events` 테이블 | `V3__create_orders_...sql` | `published`, `published_at`, `publish_attempts` 컬럼 포함 |
| `DomainEvent` 엔티티 | `event/domain/DomainEvent.java` | Outbox 필드 완비 |
| `DomainEventRecorder` | `event/service/DomainEventRecorder.java` | 6종 이벤트 DB 저장 |
| `Dockerfile` | 루트 | 멀티스테이지 빌드 |

없는 것: `docker-compose.yml`, Kafka 의존성, WebSocket 의존성, OutboxPublisher, WebSocketBroadcaster

---

## 이슈 #19 — Kafka 환경 구성 (feat/19/kafka-setup)

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
        return TopicBuilder.name("coinflow.order.events").partitions(4).replicas(1).build();
    }
    @Bean
    public NewTopic tradeEventsTopic() {
        return TopicBuilder.name("coinflow.trade.events").partitions(4).replicas(1).build();
    }
}
```

파티션을 4개로 설정하는 이유:  
현재는 단일 Consumer지만, 향후 Consumer를 수평 확장할 때 파티션 수가 상한이 된다.  
Topic 생성 후 파티션 수를 줄이는 것은 불가능하므로 여유 있게 설정한다.

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

## 이슈 #20 — Outbox Publisher (feat/20/outbox-publisher)

### 목표

`domain_events.published=false` 이벤트를 1초마다 폴링해 Kafka로 발행한다.  
발행 성공 시 `published=true`, 실패 시 `publish_attempts++`.  
5회 실패한 이벤트는 dead-letter로 분류해 폴링에서 제외한다.

### 핵심 설계 결정

| 항목 | 결정 | 이유 |
|------|------|------|
| send 방식 | `kafkaTemplate.send(...).get()` (동기) | 비동기 send는 ACK 전에 메서드가 끝날 수 있어 실패를 감지 못함 |
| 스케줄링 방식 | `fixedDelay` | `fixedRate`는 이전 실행이 끝나지 않아도 다음 실행이 시작 → 중복 처리 위험 |
| 배치 크기 | 최대 100건 | 단일 폴링 주기 내 처리량 상한. DB 부하 통제 |
| MAX_ATTEMPTS | 5 | 일시적 Kafka 장애는 5회 안에 해소된다고 가정 |
| 파티션 키 | marketSymbol | 같은 시장 이벤트 → 같은 파티션 → Consumer 순서 보장 |
| 트랜잭션 범위 | 배치 단위 `@Transactional` | 이벤트 하나 실패 시 해당 이벤트만 attempts 증가, 나머지는 published 처리 |

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

엔티티가 자신의 상태 전환을 메서드로 캡슐화한다.  
외부에서 필드를 직접 수정하지 않는다.

**2. DomainEventRepository.java — Outbox 쿼리 추가**

```java
// published=false이고 attempts < MAX_ATTEMPTS인 이벤트를 id 오름차순으로 최대 100건 조회
// → 오래된 이벤트부터 발행 (FIFO 보장)
List<DomainEvent> findTop100ByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(int maxAttempts);
```

**3. OutboxPublisher.java** (신규, `com.coinflow.event.service`)

```java
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
        return objectMapper.writeValueAsString(Map.of(
            "eventId",      event.getId(),
            "eventType",    event.getEventType().name(),
            "marketSymbol", event.getMarketSymbol(),
            "payload",      event.getPayload()
        ));
    }
}
```

**4. CoinflowApplication.java**

```java
@EnableScheduling  // 추가
```

### Kafka 메시지 포맷

```json
{
  "eventId":      1,
  "eventType":    "TRADE_CREATED",
  "marketSymbol": "BTC-KRW",
  "payload":      "{\"tradeId\":1,\"price\":\"100000000\",\"quantity\":\"0.0001\",\"quoteAmount\":\"10000\"}"
}
```

`payload`는 이미 JSON 문자열로 직렬화되어 있으므로 그대로 포함한다.  
Consumer는 `payload`를 다시 역직렬화해서 사용한다.

### Dead-letter 모니터링

```sql
-- publish_attempts >= 5인 이벤트 목록
SELECT id, event_type, market_symbol, payload, created_at, publish_attempts
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
# 주문 체결 후 1~2초 내 메시지 확인
docker exec coinflow-kafka-1 kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic coinflow.trade.events \
  --from-beginning

# 출력 예시
{"eventId":1,"eventType":"TRADE_CREATED","marketSymbol":"BTC-KRW","payload":"{...}"}
```

---

## 이슈 #21 — WebSocket 실시간 broadcast (feat/21/websocket)

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
        registry.addEndpoint("/ws")         // wscat 등 raw WebSocket 클라이언트용
                .setAllowedOriginPatterns("*");
    }
}
```

**3. WebSocket 메시지 DTO** (`com.coinflow.websocket.dto`)

```java
// 체결 피드: /topic/trades/{market}
record TradeMessage(
    String market,
    String price,
    String quantity,
    String side,        // BUY or SELL (taker 기준)
    String tradedAt
) {}

// 오더북 스냅샷: /topic/orderbook/{market}
record OrderBookMessage(
    String market,
    List<PriceLevel> buySide,   // 높은 가격 우선
    List<PriceLevel> sellSide   // 낮은 가격 우선
) {}

record PriceLevel(String price, String quantity) {}
```

**4. WebSocketBroadcaster.java** (신규, `com.coinflow.websocket`)

```java
@Component
@RequiredArgsConstructor
public class WebSocketBroadcaster {

    private final SimpMessagingTemplate messaging;
    private final MatchingEngine matchingEngine;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "coinflow.trade.events", groupId = "coinflow-websocket")
    public void onTradeEvent(String raw) {
        KafkaMessage msg = parse(raw);
        if ("TRADE_CREATED".equals(msg.eventType())) {
            TradeMessage trade = buildTradeMessage(msg);
            messaging.convertAndSend("/topic/trades/" + msg.marketSymbol(), trade);
        }
    }

    @KafkaListener(topics = "coinflow.order.events", groupId = "coinflow-websocket")
    public void onOrderEvent(String raw) {
        KafkaMessage msg = parse(raw);
        Set<String> triggers = Set.of(
            "ORDER_ACCEPTED", "ORDER_FILLED", "ORDER_PARTIALLY_FILLED", "ORDER_CANCELED"
        );
        if (triggers.contains(msg.eventType())) {
            OrderBookMessage snapshot = buildSnapshot(msg.marketSymbol());
            messaging.convertAndSend("/topic/orderbook/" + msg.marketSymbol(), snapshot);
        }
    }
}
```

`MatchingEngine`에서 현재 오더북 상태를 읽어 스냅샷을 만든다.  
메모리 오더북은 이미 최신 상태를 유지하고 있으므로 별도 DB 조회 없이 broadcast 가능하다.

**5. SecurityConfig.java 수정**

```java
.requestMatchers("/ws/**").permitAll()
```

### 변경 파일 요약

| 파일 | 작업 |
|------|------|
| `config/WebSocketConfig.java` | 신규 |
| `websocket/WebSocketBroadcaster.java` | 신규 |
| `websocket/dto/TradeMessage.java` | 신규 |
| `websocket/dto/OrderBookMessage.java` | 신규 |
| `websocket/dto/PriceLevel.java` | 신규 |
| `build.gradle` | `spring-boot-starter-websocket` 추가 |
| `config/SecurityConfig.java` | `/ws/**` permitAll 추가 |

### 완료 기준

```bash
# wscat 설치: npm install -g wscat
wscat -c ws://localhost:8080/ws

# STOMP CONNECT 프레임 전송 후 구독
SUBSCRIBE /topic/trades/BTC-KRW

# 별도 터미널에서 매수/매도 주문 체결 → 아래 메시지 수신 확인
{"market":"BTC-KRW","price":"100000000","quantity":"0.0001","side":"BUY","tradedAt":"..."}
```

---

## 이슈 #22 — E2E 통합 테스트 (feat/22/e2e)

### 목표

주문 체결 → Kafka 발행 → WebSocket 수신 전 구간을 자동으로 검증한다.  
CI에서 Docker 없이 실행되어야 한다 → `@EmbeddedKafka` 사용.

### 테스트 시나리오

| ID | 시나리오 | 검증 항목 |
|----|----------|-----------|
| EVT-E2E-001 | 주문 체결 → `coinflow.trade.events`에 `TRADE_CREATED` 발행 | eventType, marketSymbol, payload.price |
| EVT-E2E-002 | 주문 취소 → `coinflow.order.events`에 `ORDER_CANCELED` 발행 | eventType, payload.orderId |
| EVT-E2E-003 | Kafka 발행 실패 → `publish_attempts` 1 증가 | attempts == 1, published == false |
| EVT-E2E-004 | `publish_attempts >= 5` → 폴링 제외 | 6번째 poll에서 해당 이벤트 미발행 |
| WS-E2E-001 | 주문 체결 → `/topic/trades/BTC-KRW` 수신 | price, quantity 일치, 5초 내 수신 |

### 작업 목록

**1. KafkaPublishingTest.java** (신규, `com.coinflow.e2e`)

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfig.class)
@EmbeddedKafka(partitions = 1, topics = {"coinflow.order.events", "coinflow.trade.events"})
class KafkaPublishingTest {

    @Autowired OutboxPublisher outboxPublisher;
    @Autowired DomainEventRepository domainEventRepository;

    @Test
    void 주문_체결_시_TRADE_CREATED_Kafka_발행() throws Exception {
        // given: 매수자/매도자 회원가입 + 입금 + 매수/매도 주문 생성 (REST API)
        // when:  outboxPublisher.publish() 직접 호출
        // then:  KafkaTestUtils.getRecords()로 메시지 수신 후 eventType assert
    }
}
```

**2. OutboxRetryTest.java** (신규)

```java
// KafkaTemplate을 Mock으로 교체 → send() 예외 발생하도록 설정
// outboxPublisher.publish() 호출
// then: publish_attempts == 1, published == false
// 5회 반복 후 6번째 publish() 호출 → 해당 이벤트 미처리 확인
```

**3. WebSocketBroadcastTest.java** (신규)

```java
// WebSocketStompClient 생성
// /ws 연결 → /topic/trades/BTC-KRW 구독
// 주문 체결 API 호출
// CompletableFuture<TradeMessage>.get(5, SECONDS) 대기
// price, quantity assert
```

### 변경 파일 요약

| 파일 | 작업 |
|------|------|
| `e2e/KafkaPublishingTest.java` | 신규 |
| `e2e/OutboxRetryTest.java` | 신규 |
| `e2e/WebSocketBroadcastTest.java` | 신규 |

---

## 전체 구현 순서

```
feat/20/wallet PR 완료
    ↓
feat/19/kafka-setup ── docker-compose + build.gradle + KafkaTopicConfig
    검증: docker compose up → Topic 생성 확인
    ↓
feat/20/outbox-publisher ── OutboxPublisher + Repository 쿼리 + @EnableScheduling
    검증: kafka-console-consumer에서 메시지 1~2초 내 수신
    ↓
feat/21/websocket ── WebSocketConfig + WebSocketBroadcaster + DTO
    검증: wscat 구독 후 주문 체결 → 메시지 수신
    ↓
feat/22/e2e ── EmbeddedKafka + STOMP 클라이언트 테스트
    검증: ./gradlew test 전체 통과
```

---

## 면접 Q&A 준비

| 예상 질문 | 답변 포인트 |
|-----------|------------|
| 왜 Kafka를 썼나? | 체결 이벤트 하나를 여러 소비자(WebSocket, 통계, 리스크)가 독립적으로 처리해야 해서. OrderService가 소비자를 알 필요 없음 |
| Outbox Pattern이 뭔가? | DB 트랜잭션과 Kafka 발행은 하나로 묶을 수 없음. 이벤트를 DB에 먼저 저장하고 별도 프로세스가 폴링해서 발행 → 이벤트 유실 0 |
| 왜 동기 send인가? | `.get()`으로 ACK 확인 후 `published=true`. 비동기면 Kafka 실패를 감지 못하고 이벤트가 유실될 수 있음 |
| 중복 발행은 어떻게 처리하나? | at-least-once를 감수. WebSocket broadcast는 중복이 UX에만 영향. DB 소비자 추가 시 `eventId` 기준 dedup |
| 파티션 키를 왜 marketSymbol로? | BTC-KRW 이벤트는 항상 같은 파티션 → Consumer가 시장 단위 순서를 보장받음 |
| 오더북을 왜 스냅샷으로? | delta는 클라이언트가 로컬 상태를 유지해야 하고, 패킷 유실 시 불일치. 스냅샷은 항상 최신 상태 보장 |
| polling의 한계는? | 최대 1초 딜레이. Debezium CDC로 전환하면 binlog 기반 실시간 감지, 수십 ms로 줄어듦 |
