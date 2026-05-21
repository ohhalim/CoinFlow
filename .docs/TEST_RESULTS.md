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
- Phase 1 MVP 기준에서는 Kafka/WebSocket 도입 전에 주문/체결/조회 API의 기본 안정성 검증이 완료된 상태로 판단했다.

## 8. Outbox Publisher / Kafka 발행 검증

Date: 2026-05-18

Context:

| 항목 | 값 |
|---|---|
| Issue | `#36` Outbox Publisher 기반 Kafka 이벤트 발행 |
| Branch | `feat/36/outbox-publisher` |
| DB | Testcontainers MySQL 8 |
| Kafka | Embedded Kafka |

Commands:

```bash
./gradlew test --tests com.coinflow.event.service.OutboxPublisherTest
./gradlew test --tests com.coinflow.integration.KafkaPublishingIntegrationTest
./gradlew test
```

Result:

| 항목 | 값 |
|---|---:|
| OutboxPublisher unit tests | Passed, `5` tests |
| Kafka publishing integration test | Passed, `1` test |
| Full regression test | Passed |
| Total tests | `136` |
| Failed tests | `0` |
| Full regression duration | `3m 1s` |

Verified scope:

- `domain_events.published=false` 이벤트를 Kafka topic으로 발행한다.
- 주문 이벤트는 `coinflow.order.events`, 체결/정산 이벤트는 `coinflow.trade.events`로 라우팅한다.
- Kafka message key는 `marketSymbol`을 사용한다.
- 발행 성공 시 `published=true`, `published_at`이 기록되고 `last_error_message`가 초기화된다.
- 발행 실패 시 주문/체결 트랜잭션과 분리되어 `publish_attempts`와 `last_error_message`만 갱신된다.
- 한 이벤트 발행 실패가 같은 배치의 다음 이벤트 발행을 막지 않는다.
- `maxAttempts` 이상 실패한 이벤트는 폴링 대상에서 제외되는 쿼리 조건을 사용한다.
- 주문 체결 후 실제 Embedded Kafka에서 `TRADE_CREATED` 메시지를 수신했다.

Finding:

- Outbox Publisher 추가 후 기존 Phase 1 정합성 테스트가 회귀 없이 통과했다.
- 이 시점에는 WebSocket consumer/broadcast를 범위에 포함하지 않고 다음 이슈로 분리했다.

## 9. WebSocket 체결 알림 검증

Date: 2026-05-18

Context:

| 항목 | 값 |
|---|---|
| Issue | `#38` Kafka Consumer 기반 WebSocket 체결 알림 |
| Branch | `feat/38/websocket-trade-feed` |
| DB | Testcontainers MySQL 8 |
| Kafka | Embedded Kafka |

Commands:

```bash
./gradlew test --tests 'com.coinflow.websocket.*'
./gradlew test --tests com.coinflow.integration.WebSocketTradeFeedIntegrationTest
./gradlew test
```

Result:

| 항목 | 값 |
|---|---:|
| WebSocket unit tests | Passed, `6` tests |
| WebSocket Kafka integration test | Passed, `1` test |
| Full regression test | Passed |
| Total tests | `143` |
| Failed tests | `0` |
| Full regression duration | `3m 16s` |

Verified scope:

- STOMP endpoint `/ws`와 simple broker `/topic`을 설정한다.
- Kafka `coinflow.trade.events` topic의 `TRADE_CREATED` 이벤트를 소비한다.
- Outbox payload를 WebSocket 체결 메시지로 변환한다.
- `takerOrderId` 기준으로 체결 side `BUY`/`SELL`을 계산한다.
- 체결 메시지를 `/topic/trades/{market}`로 broadcast한다.
- 체결 이벤트가 아닌 메시지는 broadcast하지 않는다.
- Kafka message 파싱 실패가 발생해도 listener 예외를 외부로 전파하지 않는다.
- 주문 체결 후 Outbox Publisher가 Kafka에 발행한 `TRADE_CREATED` 이벤트가 WebSocket broadcast까지 이어지는 경로를 Embedded Kafka 통합 테스트로 검증했다.

Finding:

- WebSocket 체결 피드는 주문/체결 DB 트랜잭션과 분리된 외부 전파 계층으로 추가됐다.
- 기존 Phase 1 정합성 테스트와 Outbox Publisher 검증은 회귀 없이 통과했다.
- 이 시점의 범위는 체결 feed만 포함했다. 오더북 broadcast, WebSocket 인증/권한 분리, 클라이언트 재연결 처리는 후속 범위로 두었다.

## 10. WebSocket STOMP 실제 수신 E2E 검증

Date: 2026-05-19

Context:

| 항목 | 값 |
|---|---|
| Issue | `#40` WebSocket STOMP 실제 수신 E2E 검증 |
| Branch | `feat/40/websocket-stomp-e2e` |
| DB | Testcontainers MySQL 8 |
| Kafka | Embedded Kafka |
| WebSocket client | `WebSocketStompClient` + `StandardWebSocketClient` |

Command:

```bash
./gradlew test --tests com.coinflow.integration.WebSocketStompE2eTest
./gradlew test
```

Result:

| 항목 | 값 |
|---|---:|
| STOMP E2E integration test | Passed, `1` test |
| Full regression test | Passed |
| Total tests | `144` |
| Failed tests | `0` |
| STOMP E2E duration | `19s` |
| Full regression duration | `3m 25s` |

Verified scope:

- 실제 STOMP client가 `ws://localhost:{port}/ws`에 연결한다.
- client가 `/topic/trades/BTC-KRW`를 구독한다.
- 주문 체결 후 Outbox Publisher가 Kafka `coinflow.trade.events`에 `TRADE_CREATED` 이벤트를 발행한다.
- Kafka Consumer가 체결 이벤트를 WebSocket topic으로 broadcast한다.
- STOMP client가 `TradeFeedMessage`를 수신한다.
- 수신 메시지의 `market`, `price`, `quantity`, `side`, `tradedAt`을 검증한다.

Finding:

- 기존 #38 테스트는 서버 내부 `SimpMessagingTemplate.convertAndSend()` 호출까지 검증했다.
- 이번 테스트로 실제 WebSocket 연결, STOMP 구독, Kafka 이벤트 발행, 클라이언트 수신까지 이어지는 E2E 경로를 추가 검증했다.
- 이 시점에는 오더북 broadcast, WebSocket 인증/권한, 재연결/중복 수신 처리를 후속 범위로 두었다.

## 11. WebSocket 오더북 snapshot broadcast 검증

Date: 2026-05-19

Context:

| 항목 | 값 |
|---|---|
| Issue | `#42` WebSocket 오더북 snapshot broadcast |
| Branch | `feat/42/websocket-orderbook` |
| DB | Testcontainers MySQL 8 |
| Kafka | Embedded Kafka |
| WebSocket server side | `SimpMessagingTemplate` |

Commands:

```bash
./gradlew test --tests 'com.coinflow.websocket.*'
./gradlew test --tests com.coinflow.integration.WebSocketOrderBookBroadcastIntegrationTest
./gradlew test
```

Result:

| 항목 | 값 |
|---|---:|
| OrderBook broadcaster unit tests | Passed, `3` tests |
| OrderBook Kafka integration tests | Passed, `3` tests |
| Full regression test | Passed |
| Total tests | `150` |
| Failed tests | `0` |
| OrderBook integration duration | `21s` |
| Full regression duration | `3m 37s` |

Verified scope:

- Kafka `coinflow.order.events` topic의 주문 이벤트를 소비한다.
- `ORDER_ACCEPTED`, `ORDER_PARTIALLY_FILLED`, `ORDER_FILLED`, `ORDER_CANCELED` 이벤트에서 현재 오더북 snapshot을 만든다.
- REST 오더북과 같은 `OrderBookResponse` 집계 기준을 사용해 price level을 합산한다.
- configured depth를 `1..100` 범위로 제한한다.
- 오더북 snapshot을 `/topic/orderbook/{market}`로 broadcast한다.
- 주문 생성 후 ask price level이 broadcast된다.
- 완전 체결 후 빈 오더북 snapshot이 broadcast된다.
- 주문 취소 후 빈 오더북 snapshot이 broadcast된다.
- 대상 이벤트가 아니거나 Kafka message 파싱이 실패하면 broadcast하지 않고 listener 예외를 전파하지 않는다.

Finding:

- WebSocket 외부 전파 범위가 체결 feed에서 오더북 snapshot까지 확장됐다.
- 오더북 snapshot은 DB source of truth가 아니라 인메모리 오더북 파생 상태를 전파하는 용도다.
- 기존 Phase 1 정합성 테스트와 WebSocket 체결 E2E 테스트는 회귀 없이 통과했다.
- WebSocket 인증/권한, 클라이언트 재연결/중복 수신 처리, delta orderbook streaming은 후속 범위로 둔다.

## 12. WebSocket/Kafka 실시간 전파 부하 테스트

Date: 2026-05-20

Context:

| 항목 | 값 |
|---|---|
| Issue | `#48` WebSocket/Kafka 실시간 전파 부하 테스트 |
| Branch | `test/48/websocket-kafka-load-test` |
| DB | Local Docker MySQL 8 |
| Kafka | Local Docker Kafka |
| Observability | Prometheus / Grafana |

Command:

```bash
k6 run -e WS_SUBSCRIBERS=5 -e ORDER_RATE=10 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=5 -e ORDER_RATE=30 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=5 -e ORDER_RATE=50 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=20 -e ORDER_RATE=10 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=20 -e ORDER_RATE=30 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=20 -e ORDER_RATE=50 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=50 -e ORDER_RATE=10 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=50 -e ORDER_RATE=30 k6/websocket-kafka-load-test.js
k6 run -e WS_SUBSCRIBERS=50 -e ORDER_RATE=50 k6/websocket-kafka-load-test.js
```

Result matrix:

| WS subscribers | ORDER_RATE | Status | Created orders | Created trades | Current trade messages | OrderBook messages | Trade lag p95 | Trade lag p99 | Order create p95 | HTTP failed | 5xx |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 10 | PASS | 301 | 150 | 750 | 3,005 | 1.03s | 1.06s | 46.70ms | 0.00% | 0 |
| 5 | 30 | PASS | 901 | 450 | 2,250 | 9,005 | 1.08s | 1.12s | 32.59ms | 0.00% | 0 |
| 5 | 50 | FAIL | 1,500 | 750 | 2,665 | 10,670 | 13.58s | 14.22s | 24.58ms | 0.00% | 0 |
| 20 | 10 | PASS | 301 | 150 | 3,000 | 17,340 | 1.04s | 1.07s | 61.13ms | 0.00% | 0 |
| 20 | 30 | PASS | 900 | 450 | 9,000 | 36,000 | 1.30s | 1.41s | 35.85ms | 0.00% | 0 |
| 20 | 50 | FAIL | 1,500 | 750 | 10,660 | 42,680 | 14.40s | 15.06s | 25.68ms | 0.00% | 0 |
| 50 | 10 | PASS | 301 | 150 | 7,500 | 43,350 | 1.05s | 1.08s | 59.22ms | 0.00% | 0 |
| 50 | 30 | PASS | 893 | 446 | 22,300 | 89,250 | 2.10s | 2.28s | 44.05ms | 0.00% | 0 |
| 50 | 50 | FAIL | 1,501 | 750 | 26,050 | 104,200 | 14.49s | 15.11s | 29.75ms | 0.00% | 0 |

Note:

- FAIL은 API 실패가 아니라 `ws_trade_delivery_lag p95 < 3000ms` 기준 초과를 의미한다.
- `WS_SUBSCRIBERS=50`, `ORDER_RATE=30`에서는 k6 `dropped_iterations=8`이 발생했다.
- `ORDER_RATE=50` 구간은 주문 생성과 DB 정합성은 유지되지만, 40초 WebSocket subscriber window 안에서 실시간 전파가 밀린다.

Verified scope:

- STOMP client가 `/ws`에 연결한다.
- `/topic/trades/BTC-KRW`, `/topic/orderbook/BTC-KRW`를 구독한다.
- 주문 생성 부하 중 Kafka Consumer 기반 WebSocket broadcast를 수신한다.
- WebSocket handshake는 k6 setup에서 생성한 JWT를 사용해 실제 인증 사용자 흐름으로 검증한다.
- 체결 feed는 `tradedAt` 기준 수신 지연을 측정한다. 단, 로컬 Kafka/Outbox backlog로 인한 왜곡을 피하기 위해 테스트 시작 이후 생성된 trade만 latency 샘플에 포함한다.
- 오더북 snapshot은 수신 여부와 메시지 구조를 검증한다.

Grafana observations:

| 항목 | 관측 |
|---|---|
| Outbox unpublished events | `0` |
| Kafka consumer lag | `0` for `coinflow-websocket` group |
| JVM heap used max over 30m | about `269.68 MiB` (`282773992 bytes`) |
| GC pause max over 30m | `0.023s` |
| HTTP latency | Prometheus p95 over 30m about `52.58ms`; k6 order create p95 max `61.13ms` |
| Hikari connection | active max `2`, pending max `0` |
| 5xx increase over 30m | `0`; k6 `server_errors=0` |

Finding:

- WebSocket handshake, STOMP subscribe, Kafka Consumer broadcast, client receive path가 로컬 부하에서 동작했다.
- `ORDER_RATE=30`, `WS_SUBSCRIBERS=50`까지는 trade feed p95 `2.10s`, p99 `2.28s`로 기준을 통과했다.
- `ORDER_RATE=50`에서는 subscriber 수와 관계없이 trade feed p95가 `13s~14s`대로 상승했다.
- Outbox/Kafka backlog는 테스트 종료 시점에 남지 않았으므로, 병목은 영구 적체보다는 테스트 window 안에서 Kafka Consumer -> WebSocket broadcast 전파가 따라가지 못하는 구간으로 판단한다.
- HTTP 실패율과 5xx는 0이므로, 현재 한계는 주문 API 처리보다 실시간 전파 경로에 있다.
- 앱 재기동 직후 Kafka/Outbox에 과거 이벤트 backlog가 남아 있으면 첫 실행의 `ws_trade_delivery_lag`가 크게 튈 수 있다. 기준선 측정은 backlog를 비운 뒤 또는 fresh Kafka/DB 환경에서 실행한다.

## 13. WebSocket/Kafka 실시간 전파 병목 완화

Date: 2026-05-21

Context:

| 항목 | 값 |
|---|---|
| Issue | `#49` WebSocket broadcast 병목 완화 |
| Branch | `perf/49/websocket-broadcast-bottleneck` |
| DB | Local Docker MySQL 8 |
| Kafka | Local Docker Kafka |
| Observability | Prometheus / Grafana |

Changes:

- WebSocket outbound channel executor를 명시적으로 설정했다.
- trade feed / orderbook broadcast duration, sent count를 Micrometer metric으로 기록한다.
- OrderBook snapshot은 이벤트마다 즉시 전송하지 않고 market별로 짧은 window 동안 coalescing한다.
- Outbox 기본 발행 설정을 `100 events / 1000ms`에서 `500 events / 200ms`로 조정했다.

Comparison:

| Scenario | Change point | Status | Trade lag p95 | Trade lag p99 | OrderBook messages | Order create p95 | HTTP failed | 5xx |
|---|---|---|---:|---:|---:|---:|---:|---:|
| 50 subscribers / 30 order/s | Before | PASS | 2.10s | 2.28s | 89,250 | 44.05ms | 0.00% | 0 |
| 50 subscribers / 30 order/s | After orderbook coalescing | PASS | 1.81s | 1.92s | 1,400 | 40.58ms | 0.00% | 0 |
| 50 subscribers / 30 order/s | After Outbox cadence tuning | PASS | 225ms | 242ms | 6,500 | 30.59ms | 0.00% | 0 |
| 50 subscribers / 50 order/s | Before | FAIL | 14.49s | 15.11s | 104,200 | 29.75ms | 0.00% | 0 |
| 50 subscribers / 50 order/s | After orderbook coalescing | FAIL | 14.79s | 15.42s | 1,600 | 40.71ms | 0.00% | 0 |
| 50 subscribers / 50 order/s | After Outbox cadence tuning | PASS | 250ms | 261ms | 5,950 | 45.81ms | 0.00% | 0 |

Additional observations:

| 항목 | 관측 |
|---|---|
| Outbox unpublished events after run | `0` |
| Kafka consumer lag after run | `0` for `coinflow-websocket` group |
| `websocket.trade.broadcast.duration` max | `0.0032s` |
| `websocket.orderbook.broadcast.duration` max | `0.089s` |
| Hikari connection after run | active `0`, pending `0` |
| JVM GC pause max sample | `0.012s` |

Finding:

- OrderBook coalescing은 fan-out 메시지 수를 크게 줄였지만, `ORDER_RATE=50`의 trade feed 지연은 해결하지 못했다.
- `websocket.trade.broadcast.duration` 자체는 ms 이하~수 ms 수준이므로, 주 병목은 STOMP `convertAndSend`가 아니었다.
- `ORDER_RATE=50`에서는 주문/체결당 생성되는 domain event 수가 많아 기존 Outbox 설정(`100 events / 1000ms`)이 이벤트 발행 속도를 따라가지 못했다.
- Outbox 발행 주기와 batch size를 조정하자 `50 subscribers / 50 order/s`에서도 trade feed p95가 `14.49s`에서 `250ms`로 개선됐다.

## 14. 발견 이슈

| ID | Severity | Symptom | Suspected cause | Action |
|---|---|---|---|---|
| WS-001 | MAJOR | `ORDER_RATE=50`에서 trade feed p95가 `13s~14s`대로 상승 | Outbox 발행 주기/배치가 domain event 생성 속도를 따라가지 못함 | `#49`에서 Outbox cadence 조정 후 p95 `250ms`로 개선 |

Severity:

- `BLOCKER`: 잔고 음수, 주문 수량 초과 체결, 데이터 불일치
- `MAJOR`: 5xx, lock timeout, 반복 가능한 성능 병목
- `MINOR`: 문서/로그/테스트 안정성 개선

## 15. 후속 조치

| Action | Owner | Status | Link |
|---|---|---|---|
| Add Prometheus/Grafana local observability compose |  | DONE |  |
| Run longer k6 soak test after observability setup |  | DONE |  |
| Add Outbox Publisher Kafka event publishing |  | DONE |  |
| Add Kafka Consumer WebSocket trade feed |  | DONE |  |
| Add WebSocket STOMP receive E2E test |  | DONE |  |
| Add WebSocket orderbook snapshot broadcast |  | DONE |  |
| Add WebSocket/Kafka realtime propagation load test |  | DONE |  |
| Expand WebSocket/Kafka propagation load baseline |  | DONE |  |
| Reduce WebSocket/Kafka propagation bottleneck |  | DONE |  |
