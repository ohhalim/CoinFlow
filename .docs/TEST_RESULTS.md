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
| Date | 2026-05-16 |
| Branch | `chore/32/phase1-concurrency-load-test` |
| Commit | `22ff339` + working tree |
| Java | 21 |
| Spring Boot | 3.5.x |
| DB | MySQL 8 |
| Host | local (`MacBook-Pro.local`) |
| CPU / Memory |  |
| Notes | k6 ran against local app + Docker MySQL. Local standard is Docker MySQL exposed as `3306:3306`; stop any locally installed `mysqld` that occupies port `3306`. |

## 3. JUnit / Integration

Command:

```bash
./gradlew test
```

Result:

| 항목 | 값 |
|---|---|
| Status | Passed |
| Total tests | 104 |
| Failed tests | 0 |
| Duration | 1m 19s |

Notes:

- Command completed with `BUILD SUCCESSFUL`.

## 4. Concurrency Test Results

Command:

```bash
./gradlew test --tests com.coinflow.integration.ConcurrencyIntegrationTest
```

### CON-001 동일 사용자 동시 BUY 주문

| 항목 | 값 |
|---|---|
| Status | Passed |
| Threads | 20 |
| Repeats | 10 |
| Success orders | 10 per repeat |
| Failed requests | 10 per repeat (`INSUFFICIENT_BALANCE`) |
| Final available | `0` |
| Final locked | `100000` |
| Ledger count | 10 `ORDER_LOCK` entries per repeat |
| Finding | Concurrent BUY requests did not exceed available KRW and did not create negative balances. |

### CON-002 하나의 maker 주문에 대한 동시 taker 체결

| 항목 | 값 |
|---|---|
| Status | Passed |
| Taker threads | 10 |
| Maker quantity | `0.5` |
| Total traded quantity | `0.5` per repeat |
| Maker final status | `FILLED` |
| Maker executed quantity | `0.5` |
| Maker remaining quantity | `0` |
| Finding | Concurrent taker requests did not trade more than the maker order quantity. |

### CON-003 주문 처리 중 오더북 반복 조회

| 항목 | 값 |
|---|---|
| Status | Passed |
| Writer threads | 2 |
| Reader threads | 3 |
| Read attempts | 60 per repeat |
| Exceptions | 0 |
| Finding | Orderbook reads returned HTTP 200 while concurrent order creation updated the in-memory book. |

### CON-004 주문 취소와 체결 경합

| 항목 | 값 |
|---|---|
| Status | Passed |
| Repeats | 10 |
| Final statuses observed | `CANCELED` or `FILLED` depending on race winner |
| Invariant violations | 0 |
| Finding | Cancel/fill race ended in one terminal maker state without negative wallet balances or overfilled quantity. |

## 5. k6 Load Test Results

Command:

```bash
DURATION=30s ORDER_RATE=10 QUERY_RATE=20 BUYER_COUNT=8 SELLER_COUNT=8 k6 run k6/order-flow-load-test.js
```

Date: 2026-05-18

### LOAD-001 주문 생성 중심 시나리오

| Metric | Result | Threshold |
|---|---:|---:|
| VUs | preAllocated 8 / max 20 |  |
| Duration | 30s |  |
| http_req_failed | `0.00%` | `< 1%` |
| http_req_duration p95 | `76.5ms` overall / `76.71ms` order create | `< 1000ms` |
| 5xx count | `0` | `0` |
| Created orders | `301` |  |
| Created trades | `150` |  |

Finding:

- Order creation and matching stayed within threshold with no failed HTTP requests and no 5xx responses.

### LOAD-002 조회 API 혼합 시나리오

| Metric | Result | Threshold |
|---|---:|---:|
| VUs | preAllocated 8 / max 20 |  |
| Duration | 30s |  |
| http_req_failed | `0.00%` | `< 1%` |
| http_req_duration p95 | `22.1ms` query tagged requests | `< 500ms` |
| 5xx count | `0` | `0` |

Endpoint breakdown:

| Endpoint | p95 | Error rate | Notes |
|---|---:|---:|---|
| `GET /api/v1/markets` | `16.70ms` | `0.00%` |  |
| `GET /api/v1/markets/{market}/orderbook` | `30.44ms` | `0.00%` | Highest query p95 in this run |
| `GET /api/v1/markets/{market}/trades` | `20.47ms` | `0.00%` |  |
| `GET /api/v1/wallets` | `19.69ms` | `0.00%` |  |
| `GET /api/v1/wallets/ledgers` | `30.25ms` | `0.00%` |  |
| `GET /api/v1/fills` | `13.76ms` | `0.00%` |  |

Finding:

- Query mix stayed below the 500ms p95 threshold. No failed HTTP requests or 5xx responses were observed.

## 6. Observability Smoke Results

Date: 2026-05-18

Purpose:

- Prometheus/Grafana 로컬 관측 구성이 실제 애플리케이션 메트릭을 수집하는지 확인한다.
- Docker MySQL `3306:3306` 기준에서 앱, 테스트, k6 smoke가 함께 동작하는지 확인한다.

Commands:

```bash
docker compose ps
docker compose exec -T mysql mysqladmin ping -h localhost -ucoinflow -pcoinflow
./gradlew test
DURATION=5s ORDER_RATE=2 QUERY_RATE=4 BUYER_COUNT=2 SELLER_COUNT=2 k6 run k6/order-flow-load-test.js
```

Result:

| 항목 | 값 |
|---|---|
| Docker MySQL | `coinflow-mysql-1`, `3306:3306`, `mysqld is alive` |
| Prometheus | Ready, `coinflow` target `up` |
| Grafana | `/api/health` OK |
| Gradle test | Passed |
| k6 duration | `5s` |
| k6 http_req_failed | `0.00%` |
| k6 http_req_duration p95 | `114.49ms` |
| k6 query p95 | `38.32ms` |
| k6 server_errors | `0` |
| k6 created_orders | `11` |
| k6 created_trades | `5` |
| Prometheus query | `sum(rate(http_server_requests_seconds_count[1m])) = 0.6256` |

Finding:

- Local observability stack scraped the running CoinFlow application successfully.
- Short k6 smoke generated order/query traffic without HTTP failures or 5xx responses.

## 7. Phase 1 MVP 정합성 / Soak 검증

Date: 2026-05-18

Context:

| 항목 | 값 |
|---|---|
| Issue | `#34` Phase 1 MVP 정합성 검증 |
| Branch | `test/34/phase1-mvp-integrity-tests` |
| Commit at execution | `94e7d13` |
| App | local Spring Boot, `http://localhost:8080` |
| DB | Docker MySQL 8, `3306:3306` |
| Observability | Docker Prometheus/Grafana |

### JUnit / Integration 재검증

Command:

```bash
./gradlew test --rerun-tasks
```

Result:

| 항목 | 값 |
|---|---:|
| Status | Passed |
| Total tests | `130` |
| Failed tests | `0` |
| Errors | `0` |
| Duration | `1m 36s` |

Finding:

- 공통 정합성 검증, 실패 요청 부작용 검증, 오더북 복구 검증, 동시 요청 멱등성 검증을 포함한 전체 테스트가 통과했다.

### k6 3분 Soak 테스트

Command:

```bash
k6 run -e DURATION=3m -e ORDER_RATE=10 -e QUERY_RATE=20 -e BUYER_COUNT=8 -e SELLER_COUNT=8 k6/order-flow-load-test.js
```

Scenario:

| 항목 | 값 |
|---|---:|
| Duration | `3m` |
| Order creation rate | `10 iterations/s` |
| Query mix rate | `20 iterations/s` |
| VUs | preAllocated `8 + 8`, max `20 + 20` |
| Completed iterations | `5400` |
| Interrupted iterations | `0` |

Thresholds:

| Metric | Result | Threshold |
|---|---:|---:|
| `http_req_duration` p95 | `53.95ms` | `< 1000ms` |
| `http_req_duration{type:query}` p95 | `16.69ms` | `< 500ms` |
| `http_req_failed` | `0.00%` | `< 1%` |
| `server_errors` | `0` | `0` |

Business counters:

| Metric | Result |
|---|---:|
| Created orders | `1800` |
| Created trades | `900` |
| HTTP requests | `5449` |
| Checks succeeded | `7265 / 7265` |

Endpoint latency:

| Endpoint / metric | avg | p95 | max |
|---|---:|---:|---:|
| Overall HTTP | `19.55ms` | `53.95ms` | `149.45ms` |
| Order create | `37.08ms` | `63.16ms` | `146.48ms` |
| Query tagged requests | `9.21ms` | `16.69ms` | `80.04ms` |
| `GET /api/v1/markets` | `8.91ms` | `15.62ms` | `67.03ms` |
| `GET /api/v1/markets/{market}/orderbook` | `8.24ms` | `21.26ms` | `80.04ms` |
| `GET /api/v1/markets/{market}/trades` | `10.49ms` | `15.59ms` | `33.77ms` |
| `GET /api/v1/wallets` | `10.01ms` | `15.30ms` | `65.04ms` |
| `GET /api/v1/wallets/ledgers` | `15.25ms` | `21.50ms` | `40.67ms` |
| `GET /api/v1/fills` | `7.30ms` | `11.55ms` | `31.79ms` |

Observability sample:

| Metric | Result |
|---|---:|
| Prometheus `up{job="coinflow"}` | `1` |
| Hikari active max over 10m | `1` |
| Hikari pending max over 10m | `0` |
| Heap used max over 10m | `151457072 bytes` / about `144.44 MiB` |
| GC pause max over 10m | `0.021s` |
| GC pause total over 10m | about `0.198s` |
| 5xx increase over 10m | no series observed; k6 `server_errors=0` |

Finding:

- 3분 동안 주문 생성/조회 혼합 부하가 threshold를 모두 통과했다.
- HTTP 실패율과 서버 에러는 0으로 관측됐다.
- Hikari pending connection은 0으로 유지되어 DB 커넥션 대기 병목은 보이지 않았다.
- 현재 MVP 기준에서는 Kafka/WebSocket 도입 전에 주문/체결/조회 API의 기본 안정성 검증이 완료된 상태로 판단한다.

## 8. 발견 이슈

| ID | Severity | Symptom | Suspected cause | Action |
|---|---|---|---|---|
|  |  |  |  |  |

Severity:

- `BLOCKER`: 잔고 음수, 주문 수량 초과 체결, 데이터 불일치
- `MAJOR`: 5xx, lock timeout, 반복 가능한 성능 병목
- `MINOR`: 문서/로그/테스트 안정성 개선

## 9. 후속 조치

| Action | Owner | Status | Link |
|---|---|---|---|
| Add Prometheus/Grafana local observability compose |  | DONE |  |
| Run longer k6 soak test after observability setup |  | DONE |  |
