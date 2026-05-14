# CoinFlow Phase 2 PRD

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
- 서버 재시작 후 미발행 이벤트가 자동으로 재발행된다
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
│  [domain_events] published=false ◄──────────────────┐   │
│                                                      │   │
│  [OutboxPublisher] @Scheduled(fixedDelay=1000)       │   │
│      │ KafkaTemplate.send().get()                    │   │
│      │ 성공: published=true                           │   │
│      │ 실패: publish_attempts++                       │   │
└──────┼───────────────────────────────────────────────┘   │
       │                                                    │
       ▼                                                    │
  ┌─────────────────────────┐                              │
  │         Kafka            │                              │
  │  coinflow.order.events   │                              │
  │  coinflow.trade.events   │                              │
  └──────────┬──────────────┘                              │
             │ @KafkaListener                               │
             ▼                                              │
  [WebSocketBroadcaster]                                    │
             │ SimpMessagingTemplate                        │
             ▼                                              │
  /topic/trades/{market}                                    │
  /topic/orderbook/{market}                                 │
             │                                              │
             ▼                                              │
       [Browser / Client]                                   │
```

---

## 5. Kafka Topic 설계

| Topic | 이벤트 | 파티션 키 | 파티션 수 |
|-------|--------|-----------|-----------|
| `coinflow.order.events` | ORDER_ACCEPTED, ORDER_PARTIALLY_FILLED, ORDER_FILLED, ORDER_CANCELED | marketSymbol | 4 |
| `coinflow.trade.events` | TRADE_CREATED, SETTLEMENT_COMPLETED | marketSymbol | 4 |

**파티션 키를 marketSymbol로 설정하는 이유:**
같은 시장의 이벤트가 같은 파티션으로 들어가 Consumer가 시장 단위 순서를 보장받는다.
BTC-KRW 이벤트와 ETH-KRW 이벤트는 서로 다른 파티션에서 병렬 처리된다.

---

## 6. WebSocket 채널 설계

| 채널 | 발행 트리거 | 페이로드 | 용도 |
|------|------------|----------|------|
| `/topic/trades/{market}` | TRADE_CREATED | price, quantity, side, tradedAt | 체결 피드 |
| `/topic/orderbook/{market}` | ORDER_ACCEPTED / FILLED / PARTIALLY_FILLED / CANCELED | buySide[], sellSide[] 전체 스냅샷 | 호가창 갱신 |

**오더북을 delta가 아닌 스냅샷으로 broadcast하는 이유:**
- delta 방식은 클라이언트가 로컬 상태를 유지하고 순서를 보장해야 한다
- 패킷 유실 시 클라이언트 상태가 서버와 불일치한다
- Phase 2에서는 스냅샷으로 단순화하고, Phase 3에서 delta + 체크섬으로 최적화한다

---

## 7. 구현 이슈

| 이슈 | 브랜치 | 내용 |
|------|--------|------|
| #19 | feat/19/kafka-setup | Docker Compose (MySQL + Kafka KRaft), Topic 설정, KafkaTemplate 빈 |
| #20 | feat/20/outbox-publisher | OutboxPublisher, 재시도/dead-letter 처리 |
| #21 | feat/21/websocket | STOMP WebSocket, Kafka Consumer → broadcast |
| #22 | feat/22/e2e | 주문 체결 → Kafka 발행 → WebSocket 수신 end-to-end 자동 검증 |

---

## 8. 기술 스택 추가

| 기술 | 선택 이유 |
|------|-----------|
| Apache Kafka (KRaft) | Zookeeper 없는 단순한 로컬 환경. 거래소 도메인의 표준 이벤트 브로커 |
| spring-kafka | `KafkaTemplate`, `@KafkaListener` Spring 네이티브 통합 |
| spring-websocket (STOMP) | pub/sub 구조가 명확하고 Spring 내장 지원. SockJS 폴백 포함 |
| Docker Compose | 단일 명령으로 개발 환경 재현 |

---

## 9. 성공 기준

| 기준 | 측정 방법 |
|------|-----------|
| 주문 체결 → 2초 내 WebSocket 메시지 수신 | E2E 테스트 timeout 5s |
| Kafka 재시작 후 미발행 이벤트 자동 재발행 | `published=false` 이벤트 재발행 확인 |
| `publish_attempts >= 5` 이벤트 dead-letter 분류 | SQL 조회로 확인 |
| E2E 통합 테스트 통과 | CI 기준 |

---

## 10. Phase 3 확장 방향

| 항목 | Phase 2 | Phase 3 |
|------|---------|---------|
| 이벤트 발행 방식 | Scheduler polling (1초 딜레이) | Debezium CDC (binlog 기반, 수십 ms) |
| 오더북 broadcast | 전체 스냅샷 | delta + 체크섬 |
| Consumer 확장 | 단일 앱 내 | 별도 서비스로 분리 |
| 부하 분산 | 단일 인스턴스 | Consumer group 수평 확장 |
