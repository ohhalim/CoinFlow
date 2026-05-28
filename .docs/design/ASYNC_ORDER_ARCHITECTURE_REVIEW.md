# 비동기 주문 전환 아키텍처 리뷰 종합

## 목적

- CoinFlow 단일 market 주문 생성 병목 분석 이후 전환 방향 정리
- 외부 리뷰 3건의 공통 의견과 차이점 비교
- 202 Accepted, RDB hot path 최적화, in-memory matching 전환의 우선순위 결정
- 6월 중순 포트폴리오 마감 기준 실행 계획 정리

## 현재 기준선

측정 기준:

| 항목 | 값 |
|---|---:|
| Scenario | `50 WS subscribers / 100 order/s / 5m` |
| Actual throughput | `95.36 order/s` |
| Order create p95 / p99 / max | `1.89s` / `2.07s` / `2.69s` |
| `command_queue_wait` avg / max | `1.2278s` / `2.6813s` |
| `command_worker_process` avg / max | `14.35ms` / `145.72ms` |
| `market_lock_wait` max | `0.0084ms` |
| Hikari pending | `0` |
| Kafka consumer lag | `0` |
| HTTP failed / 5xx | `0.00%` / `0` |
| WebSocket/STOMP error | `0` |

현재 병목 분리:

| 후보 | 상태 | 근거 |
|---|---|---|
| WebSocket/Kafka 전파 | 주요 병목 제외 | Kafka lag, STOMP error `0` |
| Hikari connection pool | 주요 병목 제외 | pending `0` |
| market lock wait | 주요 병목 제외 | max `0.0084ms` |
| 단일 market worker 처리량 | 주요 병목 | queue wait avg `1.2278s` |

현재 결론:

- `100 order/s` 유입량이 단일 market worker 처리량을 초과
- worker 완료 대기 시간이 HTTP 응답 latency에 포함
- 남은 개선 방향은 `worker 처리 시간 축소` 또는 `HTTP 응답과 worker 완료 대기 분리`

## 리뷰별 평가

### 리뷰 1. 한계 분석 마감 + benchmark 우선

핵심 의견:

- 현재까지의 병목 이동 과정은 포트폴리오 가치가 높음
- 202 Accepted 구현보다 동기 구조 한계 분석과 benchmark 보강 우선
- sequence lock 제거, in-memory matching benchmark 제안
- in-memory matching + async persistence는 후속 구조로 문서화

차용 가능 항목:

| 항목 | 판단 |
|---|---|
| 동기 구조 한계 분석 마감 | 채택 |
| in-memory matching benchmark | 채택 가능 |
| sequence lock 제거 실험 | 채택 가능 |
| append-only wallet 전환 | 보류 |
| Redis wallet/source of truth 전환 | 보류 |
| ledger/domain event write-behind | 보류 |

주의점:

- sequence lock을 주 병목으로 단정 금지
- benchmark는 mock이 아니라 실제 `MatchingEngine`/`MemoryOrderBook` 기준 필요
- ledger/domain event 비동기화는 outbox/원장 정합성 훼손 위험

평가:

```text
마감 안정성 기준으로 가장 보수적인 리뷰.
낮은 리스크 실험과 분석 문서 보강에 적합.
```

### 리뷰 2. 202 Accepted 핵심 구현 권장

핵심 의견:

- 202 Accepted는 거래소 API 구조와 맞는 방향
- 처리량 개선이 아니라 응답 책임 분리로 포지셔닝 필요
- ACCEPTED 재시도 잡이 현재 구조에 현실적
- REJECTED + 자산 잠금 해제 + 원장 기록은 같은 이슈 범위 필요
- LMAX는 in-memory matching 이후 검토

차용 가능 항목:

| 항목 | 판단 |
|---|---|
| 202 Accepted 방향 | 채택 가능 |
| ACCEPTED 재시도 잡 | 채택 가능 |
| worker idempotency | 필수 |
| REJECTED 보상 transaction | 필수 |
| LMAX 보류 | 채택 |

주의점:

- 6~8일 구현 가능 추정은 낙관적
- `isQueued(orderId)`는 보조 수단, 정합성 기준 아님
- worker는 DB row lock 후 `status == ACCEPTED` 확인 필요
- 기존 `Order.create()`를 바로 `ACCEPTED`로 바꾸면 동기 경로 영향 가능

평가:

```text
비동기 구현을 실제로 진행할 경우 가장 유용한 리뷰.
단, 구현 범위와 일정 리스크 관리 필요.
```

### 리뷰 3. RDB hot path 최적화 대안 검토

핵심 의견:

- 202 외에도 현재 RDB 구조 안에서 개선 가능
- worker 내부 DB I/O 배치 처리
- Redis wallet lock
- optimistic update query
- append-only DB logging

차용 가능 항목:

| 항목 | 판단 |
|---|---|
| worker 내부 DB I/O 재측정 | 채택 |
| sequence lock 제거 실험 | 채택 가능 |
| optimistic update query | 제한적 실험 가능 |
| command batch processing | 보류 |
| Redis wallet lock | 보류 |
| append-only DB logging | 보류 |

주의점:

- `wallet_ledgers`, `domain_events`는 이미 JDBC batch/merge 최적화 적용
- command batch processing은 가격-시간 우선, self-trade, 부분 체결, 실패 fallback 때문에 복잡도 높음
- Redis wallet lock은 reconciliation까지 포함한 source of truth 전환에 가까움
- optimistic update는 taker wallet 잠금에는 실험 가능하나 settlement 전체 대체는 위험

평가:

```text
대안 탐색 관점에서 의미 있음.
즉시 구현 대상은 sequence lock 제거나 제한적 query 실험 정도.
```

## 공통 결론

세 리뷰의 공통 의견:

| 주제 | 공통 결론 |
|---|---|
| 202 Accepted | 설계 방향 타당 |
| 202 효과 | 처리량 개선이 아니라 응답 책임 분리 |
| ACCEPTED 복구 | 재시도 잡이 현재 단계에 현실적 |
| worker 실패 | REJECTED + 자산 잠금 해제 + 원장 기록 필수 |
| LMAX | 지금 도입 부적절 |
| in-memory matching | 근본 개선 방향이지만 마감 전 전체 구현 리스크 큼 |
| 포트폴리오 | 측정 기반 병목 이동 스토리 가치 높음 |

## 주요 선택지 비교

| 선택지 | 해결 대상 | 기대 효과 | 리스크 | 현재 판단 |
|---|---|---:|---|---|
| 202 Accepted | HTTP 응답 대기 | 접수 latency 감소 | 복구/보상/상태 전이 복잡도 | 가능, 단 범위 제한 |
| ACCEPTED 재시도 잡 | queue 누락 복구 | 비동기 안정성 확보 | idempotency 필요 | 채택 가능 |
| REJECTED 보상 처리 | worker 실패 복구 | 자산 잠금 누수 방지 | 원장/이벤트 정합성 필요 | 필수 |
| sequence lock 제거 | worker DB hot path | worker 처리 시간 일부 감소 | sequence 복구/단조성 설계 필요 | 우선 실험 후보 |
| optimistic update | wallet lock overhead | 일부 lock 제거 가능 | settlement 정합성 복잡 | 제한적 검토 |
| command batch processing | commit/round-trip 감소 | 처리량 증가 가능 | 실패 fallback 복잡 | 보류 |
| Redis wallet lock | DB wallet lock 제거 | latency 감소 가능 | reconciliation 필요 | 보류 |
| append-only wallet | UPDATE 제거 | lock wait 감소 가능 | 조회/정합성 모델 변경 | 보류 |
| LMAX Disruptor | queue overhead | 현재 효과 낮음 | 명분 부족 | 보류 |
| in-memory matching + async persistence | worker 처리량 한계 | 근본 개선 | replay/journal/projection 복잡 | 후속 설계 |

## 현재 최종 판단

### 1. #82는 현재 범위로 마감

현재 완료 범위:

- `AcceptedOrderResponse` 추가
- `ACCEPTED`, `REJECTED` 상태 추가
- `ACCEPTED` 취소 가능 기준 추가
- API/설계 문서 반영

마감 이유:

- 실제 `202 Accepted` 전환 전 API 계약과 상태 기준 고정
- 동기 주문 경로 영향 최소화
- 다음 구현 이슈의 기준점 확보

### 2. 바로 202 전체 전환 전 마지막 저위험 실험

우선 후보:

```text
perf: market sequence 발급 DB lock 제거
```

선정 이유:

- market별 command queue/worker가 이미 market 내 순서 보장
- 매 주문마다 DB row lock으로 sequence 발급하는 구조가 중복일 가능성
- 변경 범위가 비교적 작음
- 실패 시 원복 비용 낮음
- worker 처리 시간 감소 가능성 존재

주의점:

- sequence lock이 주 병목이라는 단정 금지
- 실험 전후 `sequence_lock`, `command_worker_process`, `order create p95`, `queue_wait` 비교 필요
- sequence는 연속성보다 market 내 단조 증가와 ordering 보장이 핵심

구현 후보:

```text
MarketSequenceAllocator
  -> 서버 시작 시 market별 max(sequence) 기준 초기화
  -> market worker에서 메모리 sequence 발급
  -> 주문 row에는 발급 sequence 저장
```

더 안전한 후속안:

```text
DB sequence block allocation
  -> 일정 크기 block 예약
  -> 메모리에서 next sequence 발급
  -> block 소진 시 DB 갱신
```

### 3. 202 Accepted 구현 조건

202 전환에 들어가기 위한 최소 조건:

- 접수 transaction에서 `ACCEPTED` 주문 저장
- 접수 transaction에서 자산 잠금
- worker는 DB row lock 후 `ACCEPTED` 상태만 처리
- 중복 queue 등록 시 worker idempotency 보장
- worker 실패 시 `REJECTED`
- `REJECTED` 처리 시 자산 잠금 해제
- `WalletLedger` 보상 기록
- `ORDER_REJECTED` 이벤트 저장
- 오래된 `ACCEPTED` 주문 재등록 스케줄러

이 조건 없이 `202 Accepted`만 적용하면 위험:

```text
queue 누락 주문이 ACCEPTED 상태로 고착
locked balance 영구 묶임
worker 실패 시 자산 복구 누락
```

## 추천 실행 순서

### 기본 로드맵

| 순서 | 작업 | 목적 |
|---:|---|---|
| 1 | #82 PR 마감 | API 계약/상태 기준 고정 |
| 2 | market sequence lock 제거 실험 | 저위험 worker hot path 개선 |
| 3 | 100 order/s 재측정 | 개선 효과 확인 |
| 4 | matching only benchmark | DB persistence 병목 근거 보강 |
| 5 | 202 Accepted 구현 여부 결정 | 일정/리스크 재평가 |

### 202 진행 시 로드맵

| 순서 | 작업 | 완료 기준 |
|---:|---|---|
| 1 | 비동기 전용 accepted order 생성 경로 | 동기 경로 영향 없음 |
| 2 | 접수 transaction 분리 | 자산 잠금 + ACCEPTED 저장 |
| 3 | worker 처리 분리 | ACCEPTED row lock 후 처리 |
| 4 | REJECTED 보상 처리 | 자산 해제 + 원장 + 이벤트 |
| 5 | ACCEPTED 재시도 잡 | 오래된 ACCEPTED 재등록 |
| 6 | 상태 전이 테스트 | ACCEPTED/OPEN/FILLED/CANCELED/REJECTED |
| 7 | 부하 재측정 | 접수 latency와 체결 latency 분리 |

### 시간이 부족한 경우 마감 범위

- #82 PR 마감
- sequence lock 제거 실험 결과 정리
- worker stage별 DB I/O 비중 표 정리
- matching only benchmark 추가
- 202 Accepted 설계와 복구 전략 문서화
- in-memory matching + async persistence를 Phase 3 후속 구조로 정리

## 포트폴리오 표현 기준

좋은 표현:

```text
DB 트랜잭션 기반 정합성 우선 거래 코어를 구현한 뒤,
k6/Grafana/Micrometer로 WebSocket, Kafka, Hikari, market lock, worker queue 병목을 분리했다.
market command queue 도입 후 lock wait는 제거됐고, 잔여 병목이 단일 market worker 처리량 한계로 이동함을 확인했다.
```

주의할 표현:

```text
202 Accepted로 처리량을 개선했다.
LMAX Disruptor로 고성능 거래소 엔진을 만들었다.
Redis/in-memory 구조로 정합성을 보장한다.
```

권장 표현:

```text
202 Accepted는 처리량 개선이 아니라 주문 접수 응답과 체결/정산 완료의 책임 분리로 정의했다.
실제 처리량 한계는 worker 내부 DB persistence 경로에 남아 있으며,
후속 구조로 in-memory matching + durable journal + async persistence를 검토했다.
```

## 다음 이슈 후보

1순위:

```text
perf: market sequence 발급 DB lock 제거
```

작업 범위:

- market별 sequence 메모리 발급 구조 검토
- 기존 DB sequence row lock 제거 또는 block allocation 적용
- sequence ordering 정합성 테스트
- `100 order/s` 재측정
- `sequence_lock`, `command_worker_process`, `queue_wait`, `order create p95` 비교

2순위:

```text
test: 순수 매칭 엔진 성능 기준선 측정
```

작업 범위:

- DB 없이 `MatchingEngine`/`MemoryOrderBook` 기준 성능 측정
- 기존 DB 포함 worker 처리 시간과 비교
- in-memory matching 필요성 수치화

3순위:

```text
feat: 비동기 주문 접수 처리 기반 추가
```

작업 범위:

- 접수 transaction 분리
- ACCEPTED 재시도 잡
- worker idempotency
- REJECTED 보상 처리
- 상태 전이/정합성 테스트
