# Worker DB I/O 비중 분석

## 목적

- sequence lock 제거 이후 잔여 병목 구간 정리
- 단일 market worker 내부 처리 시간의 DB I/O 비중 분석
- 다음 구조 변경 기준 정리

## 기준 측정

| 항목 | 값 |
|---|---:|
| 기준 이슈 | `#84` market sequence 발급 DB lock 제거 |
| 시나리오 | `50 WS subscribers / 100 order/s / 5m` |
| Created orders | `29,543` |
| Order-window throughput | `98.48 order/s` |
| Dropped iterations | `458` |
| Order create p95 / p99 / max | `1.65s` / `2.07s` / `2.43s` |
| HTTP failed / 5xx | `0.00%` / `0` |
| Hikari pending | `0` |
| Kafka consumer lag | `0` |
| WebSocket/STOMP error | `0` |

## Stage 기준

| Stage | Max | Average | 분류 |
|---|---:|---:|---|
| `command_queue_wait` | `1.8581s` | `431.93ms` | worker 대기 |
| `command_worker_process` | `85.66ms` | `9.73ms` | worker 전체 처리 |
| `market_lock_wait` | `0.0042ms` | `0.00005ms` | lock 대기 |
| `market_lock_hold` | `85.64ms` | `9.70ms` | worker 내부 직렬 처리 |
| `transaction_template` | `85.66ms` | `9.72ms` | DB transaction |
| `transaction_callback` | `71.86ms` | `6.28ms` | transaction 내부 작업 |
| `settlement` | `68.29ms` | `3.88ms` | 체결/정산 DB 작업 |
| `transaction_commit` | `68.76ms` | `3.04ms` | flush/commit |
| `sequence_allocate` | `0.039ms` | `0.001ms` | 메모리 sequence 발급 |

## 처리량 계산

| 항목 | 계산 | 값 |
|---|---:|---:|
| worker 평균 처리 시간 | `command_worker_process avg` | `9.73ms/order` |
| 단일 worker 이론 처리량 | `1000ms / 9.73ms` | 약 `102.8 order/s` |
| 테스트 유입량 | `ORDER_RATE` | `100 order/s` |
| 처리 여유 | `102.8 - 100` | 약 `2.8 order/s` |

판단:

- 단일 worker 이론 처리량과 테스트 유입량의 차이가 작다.
- 작은 지연 변동만으로도 queue depth와 `command_queue_wait` 증가 가능
- `100 order/s` 기준 p95 `1s` 미통과 원인은 worker 처리량 여유 부족과 queue 대기

## DB I/O 비중

| 기준 | 값 | 판단 |
|---|---:|---|
| `transaction_template avg / command_worker_process avg` | `9.72ms / 9.73ms` | worker 처리 대부분이 동기 DB transaction 범위 |
| `transaction_callback avg` | `6.28ms` | transaction 내부 DB write/lock/settlement 포함 |
| `settlement avg` | `3.88ms` | 체결 저장, wallet mutation, ledger/event 저장 포함 |
| `transaction_commit avg` | `3.04ms` | flush/commit 구간 영향 유지 |
| `sequence_allocate avg` | `0.001ms` | sequence 발급 병목 제외 |
| `market_lock_wait avg` | `0.00005ms` | market lock 대기 병목 제외 |

보조 근거:

- 기존 full stage 분해에서 `matching_plan max`는 `0.58ms~0.61ms` 수준
- 기존 full stage 분해에서 `settlement_order_fill`, `settlement_wallet_mutation`은 `0.03ms` 수준
- matching 계산과 entity mutation보다 DB transaction, persistence, commit 구간 비중이 높음

## 현재 병목 분리

| 후보 | 관측값 | 판단 |
|---|---:|---|
| HTTP/server error | `0%`, 5xx `0` | 제외 |
| Kafka backlog | consumer lag `0` | 제외 |
| WebSocket/STOMP 전파 | error `0` | 제외 |
| Hikari connection 대기 | pending `0` | 주요 병목 제외 |
| market lock 대기 | `0.00005ms avg` | 제외 |
| sequence 발급 | `0.001ms avg` | 제외 |
| matching 계산 | 기존 max `0.61ms` 수준 | 주요 병목 제외 |
| worker DB transaction | `transaction_template avg 9.72ms` | 주요 병목 |
| worker queue 대기 | `command_queue_wait avg 431.93ms` | 사용자 응답 p95 직접 영향 |

## 구조 후보 비교

| 후보 | 현재 병목 해결력 | 구현 난도 | 리스크 | 판단 |
|---|---:|---:|---|---|
| Virtual Thread | 낮음 | 낮음 | 낮음 | 단일 market worker 순차 처리 구조라 효과 제한 |
| LMAX Disruptor | 낮음 | 중간 | 중간 | queue 자료구조보다 worker DB transaction이 병목 |
| 단순 CQRS 조회 분리 | 낮음 | 낮음 | 낮음 | 조회 API가 현재 병목이 아님 |
| DB 튜닝/락 제거 | 중간 | 낮음~중간 | 낮음 | sequence lock 제거처럼 부분 개선에 유효 |
| 202 Accepted | HTTP 응답 분리 높음, worker 처리량 개선 낮음 | 중간 | 중간 | 응답 책임 분리와 상태 전이 설계에 유효 |
| Redis 잔고/매칭 | 높음 | 높음 | 높음 | Redis/DB 정합성, 복구, 대사 설계 필요 |
| raw Write-Behind | 높음 | 중간~높음 | 높음 | durable journal 없이 체결 유실/불일치 위험 |
| durable journal 기반 Write-Behind | 높음 | 높음 | 중간~높음 | DB I/O 분리 가능, 구현 범위 큼 |
| In-memory matching + async persistence | 매우 높음 | 매우 높음 | 높음 | 장기 구조 후보 |

## Write-Behind 판단

Write-Behind 장점:

- worker hot path에서 DB round-trip 감소 가능
- 매 주문 transaction commit 제거 가능
- batch flush 기준 처리량 개선 가능

필수 조건:

- client-visible 체결 전 durable journal 기록
- flush 실패 시 재처리 기준
- worker 재시작 시 journal replay 기준
- wallet/order/trade projection idempotency
- WebSocket 전파와 DB projection 간 순서 보장

보류 사유:

- 메모리 버퍼만 사용하는 Write-Behind는 서버 크래시 시 체결 결과 유실 가능
- WebSocket으로 체결 전파 후 DB flush 실패 시 사용자 관측 상태와 DB 상태 불일치 가능
- durable journal 없는 batch flush는 거래 정합성 설명이 어려움

결론:

- raw Write-Behind는 현재 단계에서 적용 제외
- durable journal 기반 Write-Behind는 장기 후보로 유지

## 다음 구현 기준

우선순위:

| 순서 | 후보 | 기준 |
|---:|---|---|
| 1 | 202 Accepted 주문 접수 분리 | HTTP 응답과 worker queue 대기 분리 |
| 2 | ACCEPTED 복구/REJECTED 보상 처리 | 비동기 접수 정합성 보장 |
| 3 | worker 내부 DB write 추가 축소 | API 계약 유지 범위의 부분 개선 |
| 4 | durable journal 기반 Write-Behind 설계 | 처리량 구조 개선 |
| 5 | In-memory matching + async persistence | 장기 엔진 구조 |

202 Accepted 구현 필수 범위:

- 접수 transaction에서 주문/자산 잠금 기록
- `202 Accepted` 응답 반환
- market worker 체결/정산 처리
- `ACCEPTED` 주문 재등록 job
- worker 실패 시 `REJECTED` 전이
- locked asset 해제
- 보상 원장 기록
- 상태 전이 및 잔고 정합성 E2E 테스트

## 결론

- 현재 `100 order/s` p95 미통과 원인은 WebSocket/Kafka/Hikari/market lock이 아니라 단일 market worker의 동기 DB transaction 처리량 한계
- sequence lock 제거는 worker 처리 시간을 줄인 유효한 부분 개선
- matching 계산 자체는 현재 병목에서 제외
- 다음 구조 변경은 단순 CQRS, LMAX, Virtual Thread보다 주문 접수 응답과 worker 처리 책임 분리 우선
- Write-Behind는 효과가 크지만 durable journal 없는 적용은 보류
- 다음 구현 이슈 후보: `feat: 비동기 주문 접수 transaction 분리 및 실패 보상 처리`
