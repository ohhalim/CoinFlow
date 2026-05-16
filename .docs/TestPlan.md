# CoinFlow MVP Test Plan

이 문서는 CoinFlow MVP 구현 시 검증해야 하는 핵심 테스트 시나리오를 정의한다.

테스트의 목표는 주문, 매칭, 체결, 정산, 원장, 조회 흐름에서 자산 정합성이 깨지지 않음을 증명하는 것이다.

## 1. 테스트 원칙

- 금액/수량은 `BigDecimal` 기준으로 검증한다.
- 지갑의 `available_balance`, `locked_balance`는 음수가 될 수 없다.
- 지갑 변경은 반드시 `wallet_ledgers`에 기록되어야 한다.
- 주문 수량 불변식은 항상 유지되어야 한다.
- 검증 실패 주문은 DB에 저장하지 않는다.
- 메모리 오더북에는 `OPEN`, `PARTIALLY_FILLED` 주문만 존재해야 한다.

## 2. 공통 Seed

### Assets

| asset | name | displayName | status |
|---|---|---|---|
| `KRW` | Korean Won | 원화 | ACTIVE |
| `BTC` | Bitcoin | 비트코인 | ACTIVE |

### Market

| market | base | quote | amountScale | tickSize | stepSize | minOrderQuantity | minOrderAmount |
|---|---|---|---:|---|---|---|---|
| `BTC-KRW` | BTC | KRW | `0` | `1` | `0.00000001` | `0.0001` | `5000` |

### Users / Wallets

| user | KRW available | BTC available |
|---|---:|---:|
| buyer | `1000000` | `0` |
| seller | `0` | `10` |

## 3. Auth

### AUTH-001 회원가입 성공

Given:

- 가입되지 않은 email

When:

- `POST /api/v1/auth/signup`

Then:

- user가 생성된다.
- password는 평문 저장되지 않는다.
- 응답에 password는 포함되지 않는다.
- ACTIVE 상태인 모든 asset에 대해 wallet이 생성된다 (available=0, locked=0).
- wallet 생성은 signup 트랜잭션 안에서 처리되므로 user 생성 롤백 시 wallet도 함께 롤백된다.

### AUTH-002 로그인 성공

Given:

- 가입된 사용자

When:

- 올바른 email/password로 로그인

Then:

- JWT access token이 발급된다.
- `/api/v1/users/me` 호출 시 현재 사용자 정보가 반환된다.

### AUTH-003 로그인 실패

Given:

- 가입된 사용자

When:

- 잘못된 password로 로그인

Then:

- `INVALID_CREDENTIALS` 에러가 반환된다.

## 4. Wallet / Ledger

### WAL-001 BUY 주문 lock

Given:

- buyer KRW available = `1000000`
- buyer KRW locked = `0`

When:

- buyer가 `BUY price=10000, quantity=0.5` 주문 생성

Then:

- buyer KRW available = `995000`
- buyer KRW locked = `5000`
- ledger `ORDER_LOCK`이 기록된다.
- order status = `OPEN`
- order lockedAsset = `KRW`
- order lockedAmount = `5000`

### WAL-002 SELL 주문 lock

Given:

- seller BTC available = `10`
- seller BTC locked = `0`

When:

- seller가 `SELL price=10000, quantity=0.5` 주문 생성

Then:

- seller BTC available = `9.5`
- seller BTC locked = `0.5`
- ledger `ORDER_LOCK`이 기록된다.
- order status = `OPEN`
- order lockedAsset = `BTC`
- order lockedAmount = `0.5`

### WAL-003 잔액 부족

Given:

- buyer KRW available = `1000`

When:

- buyer가 `BUY price=10000, quantity=0.5` 주문 생성

Then:

- `INSUFFICIENT_BALANCE` 에러가 반환된다.
- order가 저장되지 않는다.
- wallet balance가 변경되지 않는다.
- ledger가 기록되지 않는다.

## 5. Matching / Settlement

### MAT-001 가격 우선 매칭

Given:

- ask1: `SELL price=100000, quantity=0.1` (주문금액 10000 ≥ minOrderAmount 5000)
- ask2: `SELL price=98000, quantity=0.1` (주문금액 9800 ≥ 5000)
- ask3: `SELL price=96000, quantity=0.1` (주문금액 9600 ≥ 5000)

When:

- buyer가 `BUY price=100000, quantity=0.3` 주문 생성 (주문금액 30000 ≥ 5000)

Then:

- 체결 순서는 `96000 -> 98000 -> 100000`이다.
- trade 3건이 생성된다.

### MAT-002 시간 우선 매칭

Given:

- ask1: `SELL price=100000, quantity=0.1, sequence=1` (주문금액 10000 ≥ 5000)
- ask2: `SELL price=100000, quantity=0.1, sequence=2`

When:

- buyer가 `BUY price=100000, quantity=0.2` 주문 생성 (주문금액 20000 ≥ 5000)

Then:

- ask1이 먼저 체결된다.
- ask2가 다음에 체결된다.

### MAT-003 부분 체결

Given:

- seller order: `SELL price=100000, quantity=0.5` (주문금액 50000 ≥ 5000)

When:

- buyer가 `BUY price=100000, quantity=0.2` 주문 생성 (주문금액 20000 ≥ 5000)

Then:

- trade 1건 생성
- seller order remainingQuantity = `0.3`
- seller order status = `PARTIALLY_FILLED`
- buyer order status = `FILLED`
- seller order는 오더북에 남는다.

### MAT-004 완전 체결

Given:

- seller order: `SELL price=100000, quantity=0.5` (주문금액 50000 ≥ 5000)

When:

- buyer가 `BUY price=100000, quantity=0.5` 주문 생성 (주문금액 50000 ≥ 5000)

Then:

- trade 1건 생성
- buyer order status = `FILLED`
- seller order status = `FILLED`
- 두 주문 모두 오더북에서 제거된다.

### SET-001 BUY taker 정산

Given:

- seller가 `SELL price=98000, quantity=0.2` 주문 생성 (주문금액 19600 ≥ 5000)
- buyer KRW available = `1000000`

When:

- buyer가 `BUY price=100000, quantity=0.2` 주문 생성 (주문금액 20000 ≥ 5000)

Then:

- 체결 가격 = `98000` (maker = seller)
- trade quoteAmount = DOWN(98000 * 0.2, amountScale=0) = `19600`
- buyer KRW locked 초기값 = CEILING(100000 * 0.2, 0) = `20000`
- buyer new remaining = 0, new locked = 0, released = `20000`
- buyer KRW locked 감소 = `20000`
- buyer KRW available 환불 = released - quoteAmount = 20000 - 19600 = `400`
- buyer BTC available 증가 = `0.2`
- seller BTC locked 감소 = `0.2`
- seller KRW available 증가 = `19600`
- 정산 ledger가 기록된다.

### SET-001b BUY taker 다중 부분 체결 수치 고정

rounding이 누적되어도 공식이 깨지지 않음을 검증한다.

Given:

- ask1: `SELL price=98000, quantity=0.1` (seller, sequence=1)
- ask2: `SELL price=99000, quantity=0.1` (seller, sequence=2)
- buyer KRW available = `1000000`

When:

- buyer가 `BUY price=100000, quantity=0.2` 주문 생성 (amountScale=0)

Then:

1차 체결 (ask1, maker price=98000):
- trade quoteAmount = DOWN(98000 * 0.1, 0) = `9800`
- old_locked = CEILING(100000 * 0.2, 0) = `20000`
- new_locked = CEILING(100000 * 0.1, 0) = `10000`
- released = 20000 - 10000 = `10000`
- buyer KRW available 환불 = 10000 - 9800 = `200`

2차 체결 (ask2, maker price=99000):
- trade quoteAmount = DOWN(99000 * 0.1, 0) = `9900`
- old_locked = `10000` (1차 체결 후 갱신된 값)
- new_locked = CEILING(100000 * 0, 0) = `0`
- released = 10000 - 0 = `10000`
- buyer KRW available 환불 = 10000 - 9900 = `100`

최종:
- buyer BTC available 증가 = `0.2`
- buyer KRW 총 환불 = 200 + 100 = `300`
- buyer order status = `FILLED`, lockedAmount = `0`
- 구현에서 `old_locked`는 체결마다 갱신된 `order.lockedAmount`를 사용해야 한다. 최초 locked를 고정하거나 단순 차감하면 틀린다.

### SET-002 SELL taker 정산

Given:

- buyer가 `BUY price=100000, quantity=0.2` 주문 생성 (오더북에 OPEN 상태로 존재)
- buyer KRW locked 초기값 = CEILING(100000 * 0.2, amountScale=0) = `20000`
- seller BTC available = `10`

When:

- seller가 `SELL price=100000, quantity=0.2` 주문 생성 (taker)

Then:

- 체결 가격 = `100000` (maker = buyer)
- trade quoteAmount = DOWN(100000 * 0.2, amountScale=0) = `20000`
- seller BTC locked = `0.2` (주문 생성 시 lock)
- seller BTC locked 감소 = `0.2` (체결 정산)
- seller KRW available 증가 = `20000`
- buyer KRW locked 감소 = `20000` (new remaining = 0, new locked = 0, released = 20000)
- buyer KRW available 환불 = released - quoteAmount = 20000 - 20000 = `0`
- buyer BTC available 증가 = `0.2`
- buyer order status = `FILLED`, seller order status = `FILLED`
- 정산 ledger가 기록된다:
  - seller: `ORDER_LOCK`(BTC -0.2), `TRADE_SELL_BASE_SETTLE`(BTC locked -0.2), `TRADE_SELL_QUOTE_CREDIT`(KRW available +20000)
  - buyer: `TRADE_BUY_QUOTE_SETTLE`(KRW locked -20000), `TRADE_BUY_BASE_CREDIT`(BTC available +0.2)

### SET-004 SELF_TRADE_NOT_ALLOWED — 단순 케이스

Given:

- user1이 `SELL price=100000, quantity=0.5` 주문 생성 (주문금액 50000 ≥ 5000)

When:

- user1이 `BUY price=100000, quantity=0.5` 주문 생성 (주문금액 50000 ≥ 5000)

Then:

- 매칭 후보 목록 생성 시 user1의 SELL 주문이 발견된다.
- `SELF_TRADE_NOT_ALLOWED` 에러가 반환된다.
- taker order는 저장되지 않는다.
- wallet balance, ledger, trade 변경 없음.

### SET-005 SELF_TRADE_NOT_ALLOWED — 혼합 후보 케이스

매칭 후보 중 타인 주문과 자기 주문이 섞인 경우에도 taker 전체가 거절되어야 한다.

Given:

- user2가 `SELL price=100000, quantity=0.1, sequence=1` 주문 생성 (타인 주문, 먼저 등록)
- user1이 `SELL price=100000, quantity=0.1, sequence=2` 주문 생성 (자기 주문, 나중에 등록)

When:

- user1이 `BUY price=100000, quantity=0.2` 주문 생성

Then:

- 매칭 후보 집합 = [user2 SELL(seq=1), user1 SELL(seq=2)] — 두 주문 모두 crossing
- 후보 집합 전체를 먼저 수집한 뒤 user1의 주문이 포함되어 있음을 확인한다
- `SELF_TRADE_NOT_ALLOWED` 에러가 반환된다
- taker order는 저장되지 않는다
- user2 주문은 오더북에 그대로 유지된다
- wallet balance, ledger, trade 변경 없음

> 구현 시 주의: "탐색 중 자기 주문이 나오면 즉시 중단" 방식이면 이 케이스에서 user2 주문을 먼저 발견하고 체결을 진행할 수 있다. 반드시 crossing 후보 집합 전체를 확정한 뒤 자기 주문 여부를 검사해야 한다.

## 6. Cancel

### CAN-001 OPEN 주문 취소

Given:

- buyer가 `BUY price=10000, quantity=0.5` 주문 생성

When:

- buyer가 주문 취소

Then:

- order status = `CANCELED`
- buyer KRW available = 원래 값
- buyer KRW locked = `0`
- ledger `ORDER_CANCEL_RELEASE`가 기록된다.
- 주문은 오더북에서 제거된다.

### CAN-002 부분 체결 후 취소

Given:

- buyer order: `BUY price=10000, originalQuantity=0.5`
- `0.2` 체결됨
- remainingQuantity = `0.3`
- lockedAmount = `3000`

When:

- buyer가 주문 취소

Then:

- order status = `CANCELED`
- releasedAsset = `KRW`
- releasedAmount = `3000`
- buyer KRW locked = `0`
- remainingQuantity는 `0.3`으로 유지된다.

### CAN-003 FILLED 주문 취소 불가

Given:

- order status = `FILLED`

When:

- 주문 취소 요청

Then:

- `ORDER_NOT_CANCELABLE` 에러가 반환된다.
- wallet balance가 변경되지 않는다.
- ledger가 기록되지 않는다.

## 7. Query

### QRY-001 오더북 조회

Given:

- `OPEN`, `PARTIALLY_FILLED` 주문들이 존재

When:

- `GET /api/v1/markets/BTC-KRW/orderbook`

Then:

- bids는 가격 내림차순이다.
- asks는 가격 오름차순이다.
- 같은 가격 주문 수량은 합산된다.
- `FILLED`, `CANCELED` 주문은 포함되지 않는다.

### QRY-002 사용자 fill 조회

Given:

- buyer/seller 간 trade가 생성됨

When:

- buyer가 `GET /api/v1/fills` 호출

Then:

- buyer 주문과 관련된 fill만 반환된다.
- maker/taker 여부가 `liquidity`로 표시된다.

### QRY-003 원장 조회

Given:

- 주문 lock, 체결 정산, 취소가 발생

When:

- `GET /api/v1/wallets/ledgers`

Then:

- 로그인 사용자의 ledger만 반환된다.
- 각 ledger의 `availableBalanceAfter`, `lockedBalanceAfter`는 wallet 변경 후 값과 일치한다.

## 8. Startup

### SYS-001 오더북 초기화

Given:

- DB에 `OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELED` 주문이 존재

When:

- 애플리케이션 시작

Then:

- `OPEN`, `PARTIALLY_FILLED` 주문만 메모리 오더북에 적재된다.
- `FILLED`, `CANCELED` 주문은 적재되지 않는다.

### SYS-002 오더북 재시작 후 매칭 정합성

오더북 초기화 이후 바로 주문을 처리해도 정합성이 유지되어야 한다.

Given:

- DB에 `OPEN` 상태의 SELL 주문이 존재: `SELL price=100000, quantity=0.5`
- 애플리케이션 재시작

When:

- 재시작 후 buyer가 `BUY price=100000, quantity=0.2` 주문 생성

Then:

- SELL 주문이 오더북에 있으므로 정상 매칭된다.
- trade가 생성된다.
- SELL 주문 remainingQuantity = `0.3`, status = `PARTIALLY_FILLED`
- BUY 주문 status = `FILLED`
- wallet 정산이 정상적으로 기록된다.

### SYS-003 오더북 rebuild 이후 cancel_only 전환

commit 이후 오더북 반영 실패 시 재빌드 → 재빌드도 실패 시 cancel_only 전환 정책 검증.

Given:

- 정상 주문/체결 후 오더북 반영 중 예외 발생 시뮬레이션 (DB는 이미 commit됨)

Then:

- DB commit 데이터는 변경되지 않는다.
- 오더북 재빌드가 시도된다.
- 재빌드 성공 시: 해당 market 오더북이 DB 기준으로 복원되고, 이후 주문 처리가 정상 동작한다.
- 재빌드 실패 시: 해당 market이 `cancel_only = true`로 전환되고, 신규 주문은 `MARKET_CANCEL_ONLY`를 반환한다.

## 9. Concurrency

동시성 테스트는 market별 `ReentrantLock`, DB pessimistic lock, 지갑 잔고 불변식, 오더북 스냅샷 안정성을 검증한다.

테스트는 JUnit 5 + Testcontainers MySQL 기반으로 작성한다. 요청 시작 시점을 최대한 맞추기 위해 `ExecutorService`와 `CountDownLatch`를 사용한다.

### CON-001 동일 사용자 동시 BUY 주문

Given:

- buyer KRW available = `100000`
- 주문 1건당 필요한 lock 금액 = `10000`
- 동시에 BUY 주문 20건 요청

When:

- 모든 요청을 같은 시점에 시작한다.

Then:

- 성공 주문 수는 최대 10건이다.
- 실패 요청은 `INSUFFICIENT_BALANCE`로 끝난다.
- buyer KRW `available_balance`, `locked_balance`는 음수가 되지 않는다.
- 최종 `available + locked` 합계는 초기 KRW 잔고와 일치한다.
- `ORDER_LOCK` ledger 수는 성공 주문 수와 일치한다.

### CON-002 하나의 maker 주문에 대한 동시 taker 체결

Given:

- seller가 `SELL price=100000, quantity=0.5` 주문 1건 생성
- 여러 buyer가 각각 `BUY price=100000, quantity=0.1` 주문을 동시에 요청

When:

- buyer 10명이 동시에 주문을 생성한다.

Then:

- 총 체결 수량은 maker originalQuantity `0.5`를 초과하지 않는다.
- maker order의 `executedQuantity + remainingQuantity = originalQuantity`가 유지된다.
- maker order 상태는 `FILLED` 또는 `PARTIALLY_FILLED` 중 최종 수량과 일치한다.
- seller BTC locked는 음수가 되지 않는다.
- trade 수량 합계와 주문 executedQuantity가 일치한다.

### CON-003 주문 처리 중 오더북 반복 조회

Given:

- 한 스레드는 주문 생성/체결을 반복한다.
- 다른 스레드는 `GET /api/v1/markets/BTC-KRW/orderbook`에 해당하는 오더북 조회를 반복한다.

When:

- 두 작업을 동시에 실행한다.

Then:

- `ConcurrentModificationException`이 발생하지 않는다.
- 오더북 응답에는 `OPEN`, `PARTIALLY_FILLED` 주문만 포함된다.
- 같은 가격 레벨은 합산 수량으로 응답된다.

### CON-004 주문 취소와 체결 경합

Given:

- buyer maker 주문이 오더북에 `OPEN` 상태로 존재한다.
- 한 요청은 해당 주문 취소를 시도한다.
- 다른 요청은 교차되는 SELL taker 주문을 생성한다.

When:

- 취소와 체결 요청을 동시에 시작한다.

Then:

- 주문 최종 상태는 `CANCELED` 또는 `FILLED` 중 하나로 일관된다.
- `CANCELED`이면 trade가 생성되지 않고 잔여 locked가 해제된다.
- `FILLED`이면 취소 요청은 `ORDER_NOT_CANCELABLE` 또는 최종 상태에 맞는 에러로 끝난다.
- 어떤 경우에도 wallet balance와 ledger가 최종 주문 상태와 모순되지 않는다.

### CON-005 반복 실행 기준

동시성 테스트는 타이밍에 민감하므로 단일 성공만으로 충분하지 않다.

- 각 핵심 동시성 테스트는 최소 10회 반복 실행한다.
- 실패 시 thread별 예외, 성공/실패 응답 수, 최종 wallet/order/trade/ledger 스냅샷을 로그로 남긴다.
- 테스트가 불안정하면 구현 문제와 테스트 race를 분리하기 위해 seed 데이터와 동시 시작 barrier를 고정한다.

## 10. Local Load Test

k6 부하 테스트는 로컬 환경에서 주문 API와 조회 API의 기본 응답 특성을 기록하기 위한 테스트다. 성능 목표를 과장하지 않고, Phase 1 단일 인스턴스 구현의 현재 기준선을 남기는 데 목적이 있다.

### 실행 전제

- 로컬 MySQL 실행
- 애플리케이션 실행
- `prod` 프로필이 아닌 환경에서 dev-only deposit API 사용 가능
- Kafka/WebSocket/OutboxPublisher는 테스트 범위에 포함하지 않음

```bash
docker compose up -d mysql
./gradlew bootRun
k6 run k6/order-flow-load-test.js
```

### LOAD-001 주문 생성 중심 시나리오

Scenario:

- 테스트 사용자 회원가입 또는 로그인
- dev-only deposit API로 KRW/BTC 잔고 준비
- SELL maker 주문 생성
- BUY taker 주문 생성
- 지갑 조회
- 사용자 fill 조회

Metrics:

- `http_req_failed`
- `http_req_duration`
- 주문 생성 p95 응답 시간
- 5xx 응답 비율
- 성공한 주문 수와 생성된 trade 수

Threshold:

```javascript
thresholds: {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<1000']
}
```

### LOAD-002 조회 API 혼합 시나리오

Scenario:

- 오더북 조회
- 최근 체결 조회
- 지갑 조회
- 원장 조회
- 사용자 fill 조회

Metrics:

- 조회 API별 p95 응답 시간
- 5xx 응답 비율
- 요청 실패율

Threshold:

```javascript
thresholds: {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500']
}
```

### LOAD-003 결과 기록 기준

k6 결과는 숫자 자체보다 변화 추적이 중요하다.

- 실행 날짜
- branch / commit
- VU 수와 duration
- DB와 애플리케이션 실행 환경
- p95 응답 시간
- 실패율
- 5xx 발생 여부
- 병목으로 추정되는 API

결과는 README에 모두 붙이지 않고, [TEST_RESULTS.md](./TEST_RESULTS.md)에 실행 환경과 수치를 요약한다.

## 11. Invariants

모든 통합 테스트 후 아래 불변식을 검증한다.

```text
wallet.available_balance >= 0
wallet.locked_balance >= 0
order.original_quantity = order.executed_quantity + order.remaining_quantity
trade.quote_amount = DOWN(trade.price * trade.quantity, market.amountScale)
OPEN/PARTIALLY_FILLED 주문만 오더북에 존재
FILLED/CANCELED 주문은 오더북에 없음
```
