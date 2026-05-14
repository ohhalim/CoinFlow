# CoinFlow Phase 2 PRD

이 문서는 CoinFlow Phase 1 MVP 완료 이후 Phase 2의 제품 범위와 기술 목표를 정의한다.

## 1. 배경

Phase 1에서는 단일 인스턴스 환경에서 지정가 주문 생성/취소/매칭/정산/원장/이벤트 로그까지 이어지는 핵심 파이프라인을 완성했다.

Phase 2에서는 **분산 시스템 확장성**을 목표로 한다.  
도메인 이벤트를 Kafka로 안정적으로 발행하고, WebSocket으로 클라이언트에 실시간 push한다.

---

## 2. 목표

- 도메인 이벤트를 Outbox Pattern으로 Kafka에 안정적으로 발행한다.
- Kafka Consumer가 이벤트를 수신해 WebSocket으로 클라이언트에 실시간 broadcast한다.
- 이벤트 유실 없는 at-least-once 전달을 보장한다.
- 발행 실패 시 재시도 및 dead-letter 처리 경계를 확보한다.

---

## 3. 핵심 설계 결정

### 3-1. Outbox Pattern

**문제**  
OrderService에서 Kafka를 직접 발행하면 DB commit 성공 후 Kafka 발행 실패 시 이벤트가 유실된다.  
두 작업은 단일 트랜잭션으로 묶을 수 없다.

**해결**  
이벤트를 DB(domain_events)에 같은 트랜잭션 안에 먼저 저장(Phase 1에서 완료).  
별도 프로세스(OutboxPublisher)가 주기적으로 미발행 이벤트를 조회해 Kafka에 발행한다.

```
[OrderService]
    │ (동일 트랜잭션)
    ▼
[domain_events] published=false
    ▲ polling (1초)
[OutboxPublisher]
    │ KafkaTemplate
    ▼
[Kafka]
```

**보장**  
- Kafka 장애 시에도 이벤트는 DB에 안전하게 보관
- 서버 재시작 후 미발행 이벤트 자동 재발행
- `publish_attempts`로 재시도 횟수 추적, 임계치 초과 시 dead-letter 처리 가능

### 3-2. at-least-once 전달

Outbox polling 특성상 중복 발행이 발생할 수 있다.  
Consumer는 `event.id` 기준으로 idempotent하게 처리한다.

### 3-3. 확장 방향 (Phase 3)

Scheduler polling 방식은 최대 1초의 딜레이가 있다.  
MySQL binlog를 실시간으로 감지하는 **CDC(Change Data Capture) / Debezium**으로 전환하면  
딜레이를 수십 ms 수준으로 줄일 수 있다.

---

## 4. 아키텍처

```
[OrderService] ──(동일 트랜잭션)──▶ [domain_events] (published=false)
                                            │
                                     @Scheduled (1초)
                                            │
                                   [OutboxPublisher]
                                            │ KafkaTemplate
                                            ▼
                               ┌──────────────────────────┐
                               │          Kafka            │
                               │  coinflow.order.events    │
                               │  coinflow.trade.events    │
                               └──────────────────────────┘
                                            │ @KafkaListener
                                            ▼
                                  [WebSocketBroadcaster]
                                            │ SimpMessagingTemplate (STOMP)
                                            ▼
                            /topic/orderbook/{market}
                            /topic/trades/{market}
```

---

## 5. Kafka Topic 설계

| Topic | 포함 이벤트 | 용도 |
|-------|------------|------|
| `coinflow.order.events` | ORDER_ACCEPTED, ORDER_PARTIALLY_FILLED, ORDER_FILLED, ORDER_CANCELED | 주문 상태 실시간 알림 |
| `coinflow.trade.events` | TRADE_CREATED, SETTLEMENT_COMPLETED | 체결/정산 실시간 broadcast |

---

## 6. WebSocket 엔드포인트

| 구독 채널 | 발행 시점 | 페이로드 |
|-----------|-----------|----------|
| `/topic/trades/{market}` | TRADE_CREATED | price, quantity, side, tradedAt |
| `/topic/orderbook/{market}` | ORDER_ACCEPTED / FILLED / CANCELED | 변경된 호가창 스냅샷 |

---

## 7. 구현 이슈

| 이슈 | 브랜치 | 내용 |
|------|--------|------|
| #19 | feat/19/kafka-setup | Docker Compose (Kafka KRaft), Topic 설정, KafkaTemplate 빈 |
| #20 | feat/20/outbox-publisher | @Scheduled OutboxPublisher, 재시도/dead-letter 처리 |
| #21 | feat/21/websocket | STOMP WebSocket 설정, Kafka Consumer → broadcast |
| #22 | feat/22/e2e | 주문 → Kafka 발행 → WebSocket 수신 end-to-end 검증 |

---

## 8. 기술 스택 추가

| 기술 | 용도 |
|------|------|
| Apache Kafka (KRaft) | 메시지 브로커 |
| spring-kafka | KafkaTemplate, @KafkaListener |
| spring-websocket | STOMP WebSocket 서버 |
| spring-messaging | SimpMessagingTemplate |
| Docker Compose | 로컬 Kafka 환경 |

---

## 9. 성공 기준

- 주문 생성 → 최대 2초 내 `/topic/trades/{market}` WebSocket 메시지 수신
- Kafka 일시 중단 후 재시작 시 미발행 이벤트 자동 재발행
- publish_attempts 3회 초과 이벤트 별도 식별 가능
- end-to-end 통합 테스트 통과
