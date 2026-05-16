# CoinFlow Test Results

이 문서는 동시성 테스트와 k6 로컬 부하 테스트 실행 결과를 기록하기 위한 템플릿이다.

테스트 계획은 [TestPlan.md](./TestPlan.md)를 기준으로 하고, 이 문서는 실제 실행 환경과 결과를 남긴다. 결과가 없는 항목은 완료된 것처럼 채우지 않는다.

## 1. 실행 정책

| 테스트 | 실행 시점 | 목적 | 비고 |
|---|---|---|---|
| Unit / Integration | PR 전, CI | 기능 정합성 검증 | `./gradlew test` |
| Concurrency | PR 전, 필요 시 반복 | lock, 잔고, 주문 수량 불변식 검증 | timing-sensitive 테스트는 반복 실행 |
| k6 Load | 로컬 수동 실행, 기준선 갱신 시 | 주문/조회 API 응답 기준선 기록 | CI 필수 조건으로 두지 않음 |

## 2. 공통 환경 기록

| 항목 | 값 |
|---|---|
| Date | YYYY-MM-DD |
| Branch | `chore/32/phase1-concurrency-load-test` |
| Commit |  |
| Java | 21 |
| Spring Boot | 3.5.x |
| DB | MySQL 8 |
| Host | local |
| CPU / Memory |  |
| Notes |  |

## 3. JUnit / Integration

Command:

```bash
./gradlew test
```

Result:

| 항목 | 값 |
|---|---|
| Status | Not run |
| Total tests |  |
| Failed tests |  |
| Duration |  |

Notes:

- TBD

## 4. Concurrency Test Results

Command:

```bash
./gradlew test --tests com.coinflow.integration.ConcurrencyIntegrationTest
```

### CON-001 동일 사용자 동시 BUY 주문

| 항목 | 값 |
|---|---|
| Status | Not run |
| Threads |  |
| Repeats |  |
| Success orders |  |
| Failed requests |  |
| Final available |  |
| Final locked |  |
| Ledger count |  |
| Finding |  |

### CON-002 하나의 maker 주문에 대한 동시 taker 체결

| 항목 | 값 |
|---|---|
| Status | Not run |
| Taker threads |  |
| Maker quantity |  |
| Total traded quantity |  |
| Maker final status |  |
| Maker executed quantity |  |
| Maker remaining quantity |  |
| Finding |  |

### CON-003 주문 처리 중 오더북 반복 조회

| 항목 | 값 |
|---|---|
| Status | Not run |
| Writer threads |  |
| Reader threads |  |
| Read attempts |  |
| Exceptions |  |
| Finding |  |

### CON-004 주문 취소와 체결 경합

| 항목 | 값 |
|---|---|
| Status | Not run |
| Repeats |  |
| Final statuses observed |  |
| Invariant violations |  |
| Finding |  |

## 5. k6 Load Test Results

Command:

```bash
k6 run k6/order-flow-load-test.js
```

### LOAD-001 주문 생성 중심 시나리오

| Metric | Result | Threshold |
|---|---:|---:|
| VUs |  |  |
| Duration |  |  |
| http_req_failed |  | `< 1%` |
| http_req_duration p95 |  | `< 1000ms` |
| 5xx count |  | `0` |
| Created orders |  |  |
| Created trades |  |  |

Finding:

- TBD

### LOAD-002 조회 API 혼합 시나리오

| Metric | Result | Threshold |
|---|---:|---:|
| VUs |  |  |
| Duration |  |  |
| http_req_failed |  | `< 1%` |
| http_req_duration p95 |  | `< 500ms` |
| 5xx count |  | `0` |

Endpoint breakdown:

| Endpoint | p95 | Error rate | Notes |
|---|---:|---:|---|
| `GET /api/v1/markets/{market}/orderbook` |  |  |  |
| `GET /api/v1/markets/{market}/trades` |  |  |  |
| `GET /api/v1/wallets` |  |  |  |
| `GET /api/v1/wallets/ledgers` |  |  |  |
| `GET /api/v1/fills` |  |  |  |

Finding:

- TBD

## 6. 발견 이슈

| ID | Severity | Symptom | Suspected cause | Action |
|---|---|---|---|---|
|  |  |  |  |  |

Severity:

- `BLOCKER`: 잔고 음수, 주문 수량 초과 체결, 데이터 불일치
- `MAJOR`: 5xx, lock timeout, 반복 가능한 성능 병목
- `MINOR`: 문서/로그/테스트 안정성 개선

## 7. 후속 조치

| Action | Owner | Status | Link |
|---|---|---|---|
|  |  | TODO |  |
