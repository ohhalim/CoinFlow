# CoinFlow Phase 2 PRD

## 0. 완료 상태 요약

Phase 2는 이벤트 기반 외부 전파 계층을 추가하는 단계로 완료했다.

| 구분 | 상태 | 근거 |
|---|---|---|
| Outbox 기반 Kafka 발행 | 완료 | `#36` |
| Kafka 발행 실패/재시도 상태 관리 | 완료 | `#36` |
| WebSocket 체결 feed | 완료 | `#38` |
| 실제 STOMP client 수신 E2E | 완료 | `#40` |
| WebSocket 오더북 snapshot broadcast | 완료 | `#42` |

완료 기준은 `./gradlew test` 전체 회귀 통과와 [TEST_RESULTS.md](../TEST_RESULTS.md)에 기록된 이슈별 검증 결과다.

Phase 2에서 다루지 않은 WebSocket 인증/권한, 재연결/중복 수신 처리, delta orderbook streaming, DB Consumer idempotency, 정산 Batch는 후속 범위로 둔다.

## 1. 배경

### Phase 1에서 완성한 것

단일 인스턴스 환경에서 지정가 주문 생성 → 매칭 → 정산 → 원장 기록까지 이어지는 거래소 핵심 파이프라인을 완성했다.

### Phase 2가 필요한 이유

거래소는 체결 이벤트 하나가 발생했을 때 여러 곳에 동시에 알려야 한다.

```
체결 발생
  ├── 매수자/매도자 지갑 정산        (Phase 1 완료)
  ├── 호가창 갱신                    (Phase 1 완료, in-memory)
  ├── 체결 피드 클라이언트 broadcast  ← Phase 2
  └── 외부 시스템 (통계, 리스크 등)  ← Phase 2 이후
```

이 모든 처리를 하나의 트랜잭션에 넣으면 결합도가 폭발하고 장애 전파가 발생한다.
Phase 2의 목표는 **이벤트 기반으로 이 결합을 끊는 것**이다.

---

## 2. 해결하려는 문제

### 문제 1 — 이벤트 유실 가능성

Phase 1에서 `domain_events` 테이블에 이벤트를 DB 트랜잭션 안에 저장해뒀다.
하지만 이 이벤트를 아무도 소비하지 않는다. Kafka로 발행하는 코드가 없다.

OrderService에서 Kafka를 직접 호출하면:
```
DB commit 성공 → Kafka 발행 실패 → 이벤트 유실
```
DB와 Kafka는 단일 트랜잭션으로 묶을 수 없기 때문에 이 갭이 생긴다.

### 문제 2 — 클라이언트는 실시간을 원한다

거래소에서 가격이 REST polling으로 갱신되면 제품이 성립하지 않는다.
호가창과 체결 피드는 WebSocket push가 없으면 사용자가 쓸 수 없다.

---

## 3. 해결 방법

### 3-1. Outbox Pattern — 이벤트 유실 제거

Phase 1에서 `domain_events` 테이블에 이미 이벤트를 저장하고 있다.
이것이 Outbox다.

```
[OrderService]
    │ DB 트랜잭션 안에서 이벤트 저장 (이미 완료)
    ▼
[domain_events] published=false
    ▲
    │ @Scheduled polling (1초)
[OutboxPublisher]                ← Phase 2에서 추가
    │ KafkaTemplate.send().get() (동기, ACK 확인)
    ▼
[Kafka]
    │ published=true로 갱신
```

**보장하는 것:**
- Kafka 장애 중에도 이벤트는 DB에 안전하게 보관된다
- 서버 재시작 후에도 `published=false` 이벤트는 다음 polling 대상이 된다
- `publish_attempts`로 실패 횟수를 추적하고 임계치 초과 시 dead-letter로 분류한다

**감수하는 것:**
- at-least-once → 중복 발행이 발생할 수 있다
- Consumer는 `event.id` 기준으로 idempotent하게 처리해야 한다
- polling 방식이므로 최대 1초의 발행 딜레이가 있다

### 3-2. Kafka — 서비스 간 결합 제거

Kafka를 이벤트 버스로 두면 OrderService는 체결 사실만 기록한다.
누가 그 이벤트를 소비할지 OrderService가 알 필요가 없다.

```
OrderService → domain_events → Kafka
                                  ├── WebSocketBroadcaster (실시간 push)
                                  └── (미래) 통계 서비스, 리스크 엔진, ...
```

소비자가 늘어도 OrderService 코드를 건드리지 않는다.

### 3-3. WebSocket — 실시간 push

Kafka Consumer가 이벤트를 수신하면 STOMP WebSocket으로 클라이언트에 broadcast한다.

```
Kafka → WebSocketBroadcaster → /topic/trades/{market}
                              → /topic/orderbook/{market}
```

클라이언트는 관심 있는 시장을 구독하고, 체결/오더북 변경을 실시간으로 받는다.

---

## 4. 전체 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│                    Spring Boot App                       │
│                                                          │
│  [OrderService]                                          │
│      │ 동일 트랜잭션                                      │
│      ▼                                                   │
│  [domain_events] published=false                         │
│                                                          │
│  [OutboxPublisher] @Scheduled(fixedDelay=1000)           │
│      │ KafkaTemplate.send().get()                        │
│      │ 성공 → published=true                              │
│      │ 실패 → publish_attempts++                          │
└──────┼───────────────────────────────────────────────────┘
       │
       ▼
  ┌─────────────────────────┐
  │         Kafka            │
  │  coinflow.order.events   │
  │  coinflow.trade.events   │
  └──────────┬──────────────┘
             │ @KafkaListener
             ▼
  [WebSocketBroadcaster]
             │ SimpMessagingTemplate
             ▼
  /topic/trades/{market}        ← 체결 피드
  /topic/orderbook/{market}     ← 호가창 스냅샷
             │
             ▼
       [Browser / Client]
```

---

## 5. 정상 흐름 시퀀스

### 5-1. 주문 체결 → WebSocket 수신 전체 흐름

```
Client A       OrderService      domain_events    OutboxPublisher    Kafka     WebSocketBroadcaster   Client B
   │                │                  │                 │              │               │                 │
   │─ POST /orders ▶│                  │                 │              │               │                 │
   │                │─ match() ──────▶ │                 │              │               │                 │
   │                │─ settle() ─────▶ │                 │              │               │                 │
   │                │─ save(event) ──▶ │ published=false  │              │               │                 │
   │◀─ 200 OK ──────│                  │                 │              │               │                 │
   │                │                  │                 │              │               │                 │
   │                │                  │  @Scheduled(1s) │              │               │                 │
   │                │                  │◀─ poll ─────────│              │               │                 │
   │                │                  │─ events ───────▶│              │               │                 │
   │                │                  │                 │─ send() ───▶ │               │                 │
   │                │                  │                 │◀─ ACK ───────│               │                 │
   │                │                  │◀─ published=true─│              │               │                 │
   │                │                  │                 │              │─ @KafkaListener▶               │
   │                │                  │                 │              │               │─ convertAndSend▶│
   │                │                  │                 │              │               │  /topic/trades  │
   │                │                  │                 │              │               │                 │◀ STOMP PUSH
```

### 5-2. Kafka 장애 시 흐름

```
OutboxPublisher         Kafka         domain_events
      │                   │                 │
      │─ send() ─────────▶│                 │
      │◀─ TimeoutException─│                 │
      │─ incrementAttempts()────────────────▶│  publish_attempts=1
      │                   │                 │
      │  (1초 후 재시도)    │                 │
      │─ send() ─────────▶│                 │
      │◀─ TimeoutException─│                 │
      │─ incrementAttempts()────────────────▶│  publish_attempts=2
      │                   │                 │
      │  (Kafka 복구)      │                 │
      │─ send() ─────────▶│                 │
      │◀─ ACK ─────────────│                 │
      │─ markPublished() ──────────────────▶│  published=true
```

### 5-3. 서버 재시작 시 흐름

```
서버 재시작
    │
    ▼
OutboxPublisher @Scheduled 시작
    │
    │ published=false AND attempts < 5 인 이벤트 조회
    ▼
[이전에 발행 못한 이벤트들]
    │
    ▼
Kafka로 재발행 → published=true
```

---

## 6. 장애 시나리오 정의

| 시나리오 | 발생 시점 | 결과 | 복구 방법 |
|---------|-----------|------|-----------|
| Kafka 일시 장애 | send() 호출 시 | `publish_attempts++`, 다음 poll에서 재시도 | Kafka 복구 후 자동 재발행 |
| 서버 재시작 (정상) | publish 완료 전 | `published=false` 이벤트 남아있음 | 재시작 후 @Scheduled가 자동 재발행 |
| 서버 재시작 (send 후 ACK 전) | Kafka 수신, DB 미갱신 | `published=false`로 남아 재발행 | Consumer가 중복 수신 → idempotent 처리 |
| Kafka 영구 장애 | send() 5회 실패 | `publish_attempts=5`, 폴링 제외 | 수동 조회 후 재처리 또는 dead-letter 큐 |
| WebSocket 연결 끊김 | broadcast 시 | 해당 클라이언트만 미수신 | 클라이언트 재연결 후 REST 조회 또는 다음 snapshot 이벤트로 복구 |

---

## 7. at-least-once와 idempotency

### 중복 발행이 발생하는 경우

OutboxPublisher가 `send().get()`으로 ACK를 받은 뒤 `markPublished()`를 DB에 커밋하기 전에 서버가 죽으면,
다음 재시작 시 같은 이벤트를 다시 발행한다.

```
정상:  send → ACK → markPublished (commit) → 끝
장애:  send → ACK → [서버 크래시] → 재시작 → 같은 이벤트 재발행
```

### Consumer의 idempotent 처리

Phase 2의 WebSocket broadcast는 중복 수신이 발생해도 클라이언트 UX에만 영향을 준다.
같은 체결 메시지가 두 번 오면 화면에 같은 row가 두 번 표시될 수 있다.

DB에 결과를 저장하는 Consumer(향후 통계, 정산 등)를 추가할 때는 `event.id` 기준 dedup이 필요하다.

```sql
-- Consumer가 이미 처리한 이벤트를 기록하는 테이블 (Phase 3 이후)
CREATE TABLE processed_events (
    event_id   BIGINT      NOT NULL,
    consumer   VARCHAR(60) NOT NULL,
    processed_at DATETIME(6),
    PRIMARY KEY (event_id, consumer)
);
```

---

## 8. Kafka Topic 설계

| Topic | 이벤트 | 파티션 키 | 파티션 수 |
|-------|--------|-----------|-----------|
| `coinflow.order.events` | ORDER_ACCEPTED, ORDER_PARTIALLY_FILLED, ORDER_FILLED, ORDER_CANCELED | marketSymbol | 4 |
| `coinflow.trade.events` | TRADE_CREATED, SETTLEMENT_COMPLETED | marketSymbol | 4 |

**파티션 키를 marketSymbol로 설정하는 이유:**
같은 시장의 이벤트가 같은 파티션으로 들어가 Consumer가 시장 단위 순서를 보장받는다.
BTC-KRW 이벤트와 ETH-KRW 이벤트는 서로 다른 파티션에서 병렬 처리된다.

**파티션을 4개로 설정하는 이유:**
Consumer group의 병렬 처리 가능한 Consumer 수의 상한이 파티션 수다.
생성 후 파티션 수를 줄이는 것은 불가능하므로 여유 있게 설정한다.

---

## 9. WebSocket 채널 설계

| 채널 | 발행 트리거 | 페이로드 | 용도 |
|------|------------|----------|------|
| `/topic/trades/{market}` | TRADE_CREATED | price, quantity, side, tradedAt | 체결 피드 |
| `/topic/orderbook/{market}` | ORDER_ACCEPTED / FILLED / PARTIALLY_FILLED / CANCELED | bids[], asks[] 전체 스냅샷 | 호가창 갱신 |

**오더북을 delta가 아닌 스냅샷으로 broadcast하는 이유:**
- delta 방식은 클라이언트가 로컬 상태를 유지하고 순서를 보장해야 한다
- 패킷 유실 시 클라이언트 상태가 서버와 불일치한다
- Phase 2에서는 스냅샷으로 단순화하고, Phase 3에서 delta + 체크섬으로 최적화한다

**side 결정 방식 (TRADE_CREATED 기준):**
```
takerOrderId == buyOrderId  → side = "BUY"
takerOrderId == sellOrderId → side = "SELL"
```

---

## 10. 구현 이슈

초기 설계 문서는 `#19~#22` 단위로 계획했지만, 실제 구현은 아래 이슈들로 분리해 완료했다.

| 이슈 | 브랜치 | 내용 |
|------|--------|------|
| #36 | `feat/36/outbox-publisher` | Kafka/Outbox 설정, OutboxPublisher, 발행 성공/실패 상태 관리 |
| #38 | `feat/38/websocket-trade-feed` | Kafka Consumer 기반 WebSocket 체결 feed |
| #40 | `feat/40/websocket-stomp-e2e` | 실제 STOMP client 수신 E2E 검증 |
| #42 | `feat/42/websocket-orderbook` | Kafka 주문 이벤트 기반 오더북 snapshot broadcast |

---

## 11. 기술 스택 추가

| 기술 | 선택 이유 |
|------|-----------|
| Apache Kafka (KRaft) | Zookeeper 없는 단순한 로컬 환경. 거래소 도메인의 표준 이벤트 브로커 |
| spring-kafka | `KafkaTemplate`, `@KafkaListener` Spring 네이티브 통합 |
| spring-websocket (STOMP) | pub/sub 구조가 명확하고 Spring 내장 지원. SockJS 폴백 포함 |
| Docker Compose | 단일 명령으로 개발 환경 재현 |

---

## 12. 성공 기준

| 기준 | 측정 방법 | 상태 |
|------|-----------|---|
| 주문 체결 → 2초 내 WebSocket 메시지 수신 | E2E 테스트 timeout 5s | 완료 |
| 미발행 이벤트 재발행 경로 | `published=false` 이벤트 polling 재시도 확인 | 완료 |
| `publish_attempts >= 5` 이벤트 dead-letter 분류 | 폴링 대상 제외 테스트 | 완료 |
| 오더북 snapshot broadcast | 주문 생성/체결/취소 후 `/topic/orderbook/{market}` 검증 | 완료 |
| E2E 통합 테스트 통과 | `./gradlew test` CI 기준 | 완료 |

Kafka 프로세스를 실제로 재시작하는 운영형 장애 테스트는 Phase 2 자동 테스트 범위에 포함하지 않았다. 현재 검증은 `published=false` 이벤트가 재시도 대상에 남는 구조와 발행 실패 상태 전이를 기준으로 한다.

---

## 13. 후속 범위

Phase 2는 단일 앱 내부의 Kafka Consumer와 WebSocket broadcast까지를 완료 범위로 둔다. 아래 항목은 다음 단계에서 별도 이슈로 다룬다.

- WebSocket 연결 인증/권한 분리
- 클라이언트 재연결/중복 수신 처리
- delta orderbook streaming, sequence number, checksum
- Consumer 별도 서비스 분리
- DB에 결과를 쓰는 Consumer의 `processed_events` 기반 idempotency
- 일별 거래 정산 Batch

---

## 14. Phase 3 확장 방향

| 항목 | Phase 2 | Phase 3 |
|------|---------|---------|
| 이벤트 발행 방식 | Scheduler polling (최대 1초 딜레이) | Debezium CDC (binlog 기반, 수십 ms) |
| 오더북 broadcast | 전체 스냅샷 | delta + sequence number + 클라이언트 체크섬 |
| Consumer 구조 | 단일 앱 내 WebSocketBroadcaster | 별도 서비스로 분리, Consumer group 수평 확장 |
| idempotency | WebSocket broadcast만 (UX 허용) | DB Consumer 추가 시 processed_events 테이블로 dedup |
