# 비동기 주문 접수 설계

## 목적

- 단일 market 주문 생성 병목을 동기 HTTP 응답 구조와 체결/정산 처리 구조로 분리
- 주문 접수 응답과 체결/정산 처리 책임 분리 기준 정의
- `command_queue_wait` 중심 잔여 병목 해소 방향 검토

## 배경

`#78` market별 command queue 도입 결과: 기존 `market_lock_wait` 병목 분리

측정 결과:

| 항목 | 값 |
|---|---:|
| Scenario | `50 WS subscribers / 100 order/s / 5m` |
| Created orders | `28,608` |
| Actual order throughput | `95.36 order/s` |
| Dropped iterations | `1,393` |
| Order create p95 / p99 / max | `1.89s` / `2.07s` / `2.69s` |
| `command_queue_wait` max / avg | `2.6813s` / `1.2278s` |
| `command_worker_process` max / avg | `145.72ms` / `14.35ms` |
| `market_lock_wait` max | `0.0084ms` |
| Queue depth max | `159` |
| Hikari pending | `0` |
| HTTP failed / 5xx | `0.00%` / `0` |
| Kafka consumer lag | `0` |
| WebSocket/STOMP error | `0` |

병목 후보 분리:

| 후보 | 관측값 | 판단 |
|---|---:|---|
| market lock 대기 | `0.0084ms` max | 주요 병목 제외 |
| DB connection 대기 | Hikari pending `0` | 주요 병목 제외 |
| Kafka backlog | consumer lag `0` | 주요 병목 제외 |
| WebSocket/STOMP 전파 | error `0` | 실패 원인 제외 |
| market worker 대기 | queue wait avg `1.2278s` | 주요 잔여 병목 |

현재 주문 생성 응답 범위:

```text
POST /api/v1/orders
  -> 요청 검증
  -> clientOrderId 중복 검사
  -> market별 command queue submit
  -> market worker 처리 완료 대기
  -> DB transaction
       -> sequence 발급
       -> wallet lock
       -> 자산 잠금
       -> order 저장
       -> 매칭 계획 생성
       -> 체결 저장
       -> 지갑 정산
       -> 원장 저장
       -> domain_events 저장
  -> commit
  -> afterCommit 오더북 반영
  -> 201 Created 응답
```

현재 구조의 제약:

- HTTP 응답이 market worker 처리 완료까지 대기
- worker 처리 시간이 증가하면 `command_queue_wait`와 HTTP p95 동시 증가
- 주문 접수 성공과 체결/정산 완료가 하나의 응답 계약에 결합

## 목표 구조

기본 방향:

```text
POST /api/v1/orders
  -> 요청 검증
  -> clientOrderId 중복 검사
  -> 주문 접수 기록
  -> 필요 자산 잠금
  -> command enqueue
  -> 202 Accepted 응답

Market worker
  -> command dequeue
  -> 매칭 계획 생성
  -> 체결 저장
  -> 지갑 정산
  -> 원장 저장
  -> domain_events 저장
  -> commit
  -> 오더북 반영
  -> Kafka/WebSocket 전파
```

설계 기준:

| 항목 | 기준 |
|---|---|
| HTTP 응답 책임 | 주문 접수 결과 반환 |
| worker 책임 | 매칭, 체결 저장, 지갑 정산, 원장 기록, 이벤트 저장 |
| 응답 지연 목표 | worker queue 대기와 분리 |
| source of truth | DB 주문/체결/지갑/원장 |
| 실시간 전파 | worker 처리 완료 이후 outbox 기반 발행 |

## API 응답 정책

### 후보 A. 기존 `201 Created` 유지

| 항목 | 내용 |
|---|---|
| 응답 시점 | worker 처리 완료 이후 |
| 응답 내용 | 주문 상태, 체결 결과 포함 |
| 장점 | 기존 API 호환 |
| 한계 | `command_queue_wait`가 HTTP latency에 직접 반영 |

### 후보 B. `202 Accepted` 전환

| 항목 | 내용 |
|---|---|
| 응답 시점 | 주문 접수와 command enqueue 완료 이후 |
| 응답 내용 | `orderId`, `clientOrderId`, `status`, `acceptedAt` |
| 장점 | 주문 접수 latency와 worker 처리 latency 분리 |
| 한계 | 클라이언트가 주문 상태 조회 또는 WebSocket 결과 수신 필요 |

### 기본안

| 항목 | 결정 |
|---|---|
| 신규 API 응답 | `202 Accepted` |
| 응답 필드 | `orderId`, `clientOrderId`, `market`, `side`, `status`, `acceptedAt` |
| 기존 `CreateOrderResponse.trades` | 비동기 응답에서 제외 |
| 체결 결과 확인 | `GET /api/v1/orders/{id}`, `/topic/trades/{market}` |
| 호환성 | 기존 동기 API 유지 여부 별도 이슈에서 결정 |

### `#82` 응답 모델 결정

| 항목 | 결정 |
|---|---|
| 현재 `POST /api/v1/orders` | 기존 `201 Created` 동기 응답 유지 |
| 비동기 접수 응답 모델 | `AcceptedOrderResponse` 추가 |
| 응답 필드 | `orderId`, `clientOrderId`, `market`, `side`, `status`, `acceptedAt` |
| 체결 결과 | 접수 응답에서 제외 |
| 실제 `202 Accepted` 전환 | 주문 접수 transaction 분리 이후 |

## 상태 전이

현재 상태:

```text
OPEN -> PARTIALLY_FILLED -> FILLED
OPEN -> CANCELED
PARTIALLY_FILLED -> CANCELED
```

비동기 모델 후보:

```text
ACCEPTED
  -> OPEN
  -> PARTIALLY_FILLED
  -> FILLED
  -> REJECTED
  -> CANCELED
```

상태 기준:

| 상태 | 기준 |
|---|---|
| `ACCEPTED` | 주문 접수 기록과 command enqueue 완료 |
| `OPEN` | worker 처리 후 미체결 잔량 존재 |
| `PARTIALLY_FILLED` | 일부 체결 후 잔량 존재 |
| `FILLED` | 전체 체결 완료 |
| `REJECTED` | worker 처리 중 검증 실패 또는 정산 실패 |
| `CANCELED` | 접수 후 사용자 취소 완료 |

검토 사항:

- `OrderStatus`에 `ACCEPTED`, `REJECTED` 추가
- 현재 동기 주문 생성 경로는 `OPEN` 시작 유지
- `ACCEPTED`, `REJECTED` 실제 사용은 주문 접수 transaction 분리 범위
- `ACCEPTED` 상태 주문의 오더북 노출 여부 결정 필요

## 정합성 기준

### 자산 잠금 시점

| 후보 | 내용 | 검토 |
|---|---|---|
| 접수 transaction에서 잠금 | `202` 응답 전 available -> locked 이동 | 응답 성공 시 자산 확보 명확 |
| worker transaction에서 잠금 | worker 처리 시 자산 잠금 | 접수 후 처리 전 잔고 변동 위험 |

기본안:

- 접수 transaction에서 자산 잠금
- 자산 잠금 실패 시 `202` 응답 없음
- worker는 이미 잠긴 자산 기준으로 매칭/정산

### clientOrderId

기준:

- 접수 transaction에서 `userId + clientOrderId` unique 보장
- 중복 요청은 기존 order 기준 응답 정책 검토
- DB unique constraint 기반 최종 방어 유지

### 취소 경합

검토 케이스:

- `ACCEPTED` 상태 취소
- worker 처리 중 취소
- 체결 직후 취소

기본 기준:

- `ACCEPTED` 취소 허용
- worker가 처리 시작 전 취소 상태 확인
- worker 처리 시작 이후 기존 `OPEN/PARTIALLY_FILLED` 취소 정책 적용

### worker 실패

처리 기준:

- worker 처리 중 예외 발생 시 `REJECTED` 또는 재시도 대상 표시
- 자산 잠금 해제 원장 기록
- 실패 이벤트 outbox 저장
- 운영 재처리 대상 분리

## 이벤트 기준

| 이벤트 | 발행 시점 | 용도 |
|---|---|---|
| `ORDER_ACCEPTED` | 접수 transaction commit | 주문 접수 알림, 상태 조회 |
| `ORDER_REJECTED` | worker 실패 처리 commit | 실패 알림, 자산 복구 확인 |
| `TRADE_CREATED` | 체결 transaction commit | 체결 feed |
| `SETTLEMENT_COMPLETED` | 정산 transaction commit | 정산 완료 알림 |
| `ORDER_FILLED/PARTIALLY_FILLED` | 체결 상태 변경 commit | 주문 상태 전파 |

outbox 기준:

- DB 상태 변경 transaction 안에서 domain event 저장
- Kafka 발행은 기존 Outbox Publisher 유지
- consumer idempotency는 후속 범위

## 테스트 범위

단위/통합 테스트:

- 주문 접수 성공 시 `ACCEPTED` 상태 저장
- 접수 transaction에서 자산 잠금
- command enqueue 실패 시 transaction rollback
- worker 처리 성공 시 `OPEN/FILLED/PARTIALLY_FILLED` 전이
- worker 실패 시 `REJECTED` 전이와 자산 잠금 해제
- `ACCEPTED` 상태 취소
- `clientOrderId` 중복 요청
- self-trade rejection 경로
- 주문 상태 조회 API
- WebSocket 체결 알림

정합성 테스트:

- 잔고 음수 없음
- locked balance와 open/accepted order 합계 일치
- 체결 수량 초과 없음
- 원장 합계와 지갑 변경 일치
- outbox 이벤트와 주문 상태 전이 일치

부하 테스트:

- `50 WS subscribers`
- `100 order/s`
- `5m`
- `DB_POOL_MAX_SIZE=20`
- 동기 모델 vs 비동기 모델 비교

## 측정 항목

| 지표 | 목적 |
|---|---|
| order accept latency p95/p99 | HTTP 접수 응답 지연 |
| worker queue wait p95/p99 | 체결 처리 대기 |
| worker process time p95/p99 | market worker 처리 시간 |
| queue depth max | market별 backlog |
| order status transition lag | 접수 후 최종 상태 반영 지연 |
| trade delivery lag p95/p99 | 체결 feed 수신 지연 |
| Hikari active/pending | DB connection pressure |
| Kafka consumer lag | 전파 backlog |
| outbox unpublished count | 이벤트 발행 backlog |
| HTTP failed / 5xx | API 실패 |

## 구현 이슈 분리

| 순서 | 후보 이슈 | 범위 |
|---:|---|---|
| 1 | `feat: 비동기 주문 접수 응답 모델 추가` | response DTO, API 정책, 상태 추가 |
| 2 | `feat: 주문 접수 transaction 분리` | 접수 저장, 자산 잠금, command enqueue |
| 3 | `feat: market worker 체결 처리 분리` | worker 내부 매칭/정산 처리 |
| 4 | `test: 비동기 주문 상태 전이 검증` | 상태 전이, 취소, 실패, 정합성 테스트 |
| 5 | `perf: 비동기 주문 접수 부하 테스트` | 동기/비동기 latency 비교 |

## 제외 범위

- LMAX Disruptor 교체
- Kafka command queue 전환
- durable command journal
- multi-instance market worker partitioning
- orderbook delta streaming
- replay/redrive 자동화
- R2DBC 전환
- off-heap orderbook

## 완료 기준

- API 응답 정책 결정
- 주문 상태 전이 기준 결정
- 자산 잠금 시점 결정
- worker 실패 처리 기준 결정
- 테스트 범위 확정
- 구현 이슈 분리 가능 상태
