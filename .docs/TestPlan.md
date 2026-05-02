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

| asset | type | precision |
|---|---|---|
| `KRW` | FIAT | `1` |
| `BTC` | CRYPTO | `0.00000001` |

### Market

| market | base | quote | tickSize | stepSize | minOrderQuantity | minOrderAmount |
|---|---|---|---|---|---|---|
| `BTC-KRW` | BTC | KRW | `1` | `0.00000001` | `0.0001` | `5000` |

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

- ask1: `SELL price=10000, quantity=0.1`
- ask2: `SELL price=9900, quantity=0.1`
- ask3: `SELL price=9800, quantity=0.1`

When:

- buyer가 `BUY price=10000, quantity=0.3` 주문 생성

Then:

- 체결 순서는 `9800 -> 9900 -> 10000`이다.
- trade 3건이 생성된다.

### MAT-002 시간 우선 매칭

Given:

- ask1: `SELL price=10000, quantity=0.1, sequence=1`
- ask2: `SELL price=10000, quantity=0.1, sequence=2`

When:

- buyer가 `BUY price=10000, quantity=0.2` 주문 생성

Then:

- ask1이 먼저 체결된다.
- ask2가 다음에 체결된다.

### MAT-003 부분 체결

Given:

- seller order: `SELL price=10000, quantity=0.5`

When:

- buyer가 `BUY price=10000, quantity=0.2` 주문 생성

Then:

- trade 1건 생성
- seller order remainingQuantity = `0.3`
- seller order status = `PARTIALLY_FILLED`
- buyer order status = `FILLED`
- seller order는 오더북에 남는다.

### MAT-004 완전 체결

Given:

- seller order: `SELL price=10000, quantity=0.5`

When:

- buyer가 `BUY price=10000, quantity=0.5` 주문 생성

Then:

- trade 1건 생성
- buyer order status = `FILLED`
- seller order status = `FILLED`
- 두 주문 모두 오더북에서 제거된다.

### SET-001 BUY taker 정산

Given:

- seller가 `SELL price=9900, quantity=0.2` 주문 생성
- buyer KRW available = `1000000`

When:

- buyer가 `BUY price=10000, quantity=0.2` 주문 생성

Then:

- 체결 가격 = `9900`
- trade quoteAmount = `1980`
- buyer KRW locked 감소 = `2000`
- buyer KRW available 환불 = `20`
- buyer BTC available 증가 = `0.2`
- seller BTC locked 감소 = `0.2`
- seller KRW available 증가 = `1980`
- 정산 ledger가 기록된다.

### SET-002 SELF_TRADE_NOT_ALLOWED

Given:

- user1이 `SELL price=10000, quantity=0.5` 주문 생성

When:

- user1이 `BUY price=10000, quantity=0.5` 주문 생성

Then:

- `SELF_TRADE_NOT_ALLOWED` 에러가 반환된다.
- taker order는 저장되지 않는다.
- wallet balance가 변경되지 않는다.

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
- 각 ledger의 `availableAfter`, `lockedAfter`는 wallet 변경 후 값과 일치한다.

## 8. Startup

### SYS-001 오더북 초기화

Given:

- DB에 `OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELED` 주문이 존재

When:

- 애플리케이션 시작

Then:

- `OPEN`, `PARTIALLY_FILLED` 주문만 메모리 오더북에 적재된다.
- `FILLED`, `CANCELED` 주문은 적재되지 않는다.

## 9. Invariants

모든 통합 테스트 후 아래 불변식을 검증한다.

```text
wallet.available_balance >= 0
wallet.locked_balance >= 0
order.original_quantity = order.executed_quantity + order.remaining_quantity
trade.quote_amount = trade.price * trade.quantity
OPEN/PARTIALLY_FILLED 주문만 오더북에 존재
FILLED/CANCELED 주문은 오더북에 없음
```
