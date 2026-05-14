# CoinFlow Phase 2 구현 플랜

## 이슈 #19 — Kafka 환경 구성 (feat/19/kafka-setup)

### 목표
로컬 Kafka 환경 구성 및 Spring Boot 연동

### 작업 내용

**1. Docker Compose**
```yaml
# docker-compose.yml
kafka (KRaft 모드, Zookeeper 없음)
  - KAFKA_PROCESS_ROLES: broker,controller
  - port: 9092
```

**2. build.gradle 의존성 추가**
```
spring-kafka
```

**3. application.yml Kafka 설정**
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: StringSerializer
      value-serializer: StringSerializer
    consumer:
      group-id: coinflow-websocket
      auto-offset-reset: latest
```

**4. Topic 생성 빈**
```
coinflow.order.events  (partition: 4, replication: 1)
coinflow.trade.events  (partition: 4, replication: 1)
```

**검증**: Spring Boot 실행 시 Topic 자동 생성 확인

---

## 이슈 #20 — Outbox Publisher (feat/20/outbox-publisher)

### 목표
domain_events 테이블의 미발행 이벤트를 Kafka로 안정적으로 발행

### 작업 내용

**1. OutboxPublisher**
```java
@Scheduled(fixedDelay = 1000)
public void publish() {
    // published=false, publish_attempts < 5 인 이벤트 최대 100건 조회
    // KafkaTemplate으로 발행
    // 성공: published=true, published_at=now()
    // 실패: publish_attempts++
}
```

**2. 발행 Topic 라우팅**
```
ORDER_* 이벤트 → coinflow.order.events
TRADE_* 이벤트 → coinflow.trade.events
```

**3. Kafka 메시지 구조**
```json
{
  "eventId": 1,
  "eventType": "TRADE_CREATED",
  "aggregateId": 42,
  "marketSymbol": "BTC-KRW",
  "payload": "{...}"
}
```

**4. DomainEventRepository 쿼리 추가**
```java
findTop100ByPublishedFalseAndPublishAttemptsLessThanOrderByIdAsc(int maxAttempts)
updatePublishedTrue(Long id)
incrementPublishAttempts(Long id)
```

**검증**: 주문 생성 후 1~2초 내 Kafka 메시지 발행 확인 (kafka-console-consumer)

---

## 이슈 #21 — WebSocket 실시간 broadcast (feat/21/websocket)

### 목표
Kafka Consumer가 이벤트를 수신해 WebSocket 클라이언트에 실시간 push

### 작업 내용

**1. WebSocket 설정**
```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    // STOMP endpoint: /ws
    // topic prefix: /topic
    // app prefix: /app
}
```

**2. Kafka Consumer**
```java
@KafkaListener(topics = "coinflow.trade.events", groupId = "coinflow-websocket")
public void onTradeEvent(String message) {
    // TRADE_CREATED → /topic/trades/{market} broadcast
    // SETTLEMENT_COMPLETED → 무시 (or 추가 활용)
}

@KafkaListener(topics = "coinflow.order.events", groupId = "coinflow-websocket")  
public void onOrderEvent(String message) {
    // ORDER_FILLED / CANCELED → 오더북 변경 → /topic/orderbook/{market} broadcast
}
```

**3. WebSocket 메시지 DTO**
```
TradeMessage  { market, price, quantity, side, tradedAt }
OrderBookMessage { market, buySide[], sellSide[] }
```

**검증**: WebSocket 클라이언트(Postman or wscat) 구독 후 주문 체결 시 메시지 수신 확인

---

## 이슈 #22 — end-to-end 통합 테스트 (feat/22/e2e)

### 목표
주문 → Kafka 발행 → WebSocket 수신 전 과정 자동 검증

### 작업 내용

**1. EmbeddedKafka 테스트 환경**
```java
@EmbeddedKafka(partitions = 1, topics = {"coinflow.order.events", "coinflow.trade.events"})
```

**2. 테스트 시나리오**
```
EVT-E2E-001: 주문 체결 시 coinflow.trade.events에 TRADE_CREATED 메시지 발행 검증
EVT-E2E-002: 주문 취소 시 coinflow.order.events에 ORDER_CANCELED 메시지 발행 검증
EVT-E2E-003: Outbox 재시도 검증 (Kafka 발행 실패 시 publish_attempts 증가)
```

**3. WebSocket 수신 테스트**
```java
StompSession session = stompClient.connect("/ws", ...);
session.subscribe("/topic/trades/BTC-KRW", handler);
// 주문 체결 → handler가 메시지 수신할 때까지 대기 (CompletableFuture)
```

---

## 전체 구현 순서

```
#19 kafka-setup
    └─ Docker Compose + Spring 연동 확인
         ↓
#20 outbox-publisher  
    └─ DB → Kafka 발행 동작 확인
         ↓
#21 websocket
    └─ Kafka → WebSocket broadcast 동작 확인
         ↓
#22 e2e
    └─ 전 구간 자동 테스트
```

---

## 면접 어필 포인트 정리

| 주제 | 설명 |
|------|------|
| Outbox Pattern | "DB 트랜잭션과 메시지 발행의 원자성 문제를 Outbox로 해결" |
| at-least-once | "중복 발행 가능성 인지, consumer idempotent 처리" |
| 재시도/dead-letter | "publish_attempts로 실패 추적, 임계치 초과 시 별도 처리" |
| CDC 확장 | "현재 polling 방식, Debezium CDC로 전환 시 딜레이 수십 ms로 감소 가능" |
| 실시간 push | "STOMP WebSocket으로 클라이언트에 체결/오더북 실시간 broadcast" |
