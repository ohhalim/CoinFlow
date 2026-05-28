# CoinFlow MVP API 명세

이 문서는 CoinFlow MVP의 REST API 계약을 정의한다.

MVP API는 회원가입/로그인, 시장 조회, 주문 생성/취소, 오더북 조회, 체결/fill 조회, 지갑/원장 조회를 지원한다. 입출금, 수수료, 시장가 주문, refresh token, OAuth, 관리자 API는 제외한다.

## 1. 공통 규칙

### Base URL

```text
/api/v1
```

### 인증

인증이 필요한 API는 `Authorization` 헤더에 JWT access token을 전달한다.

```http
Authorization: Bearer {accessToken}
```

서버는 request body나 query string의 `userId`를 신뢰하지 않는다. 주문, 지갑, 원장, fill 조회는 JWT에서 추출한 현재 사용자 ID 기준으로 처리한다.

### 금액과 수량

금액, 가격, 수량은 JSON number가 아니라 string으로 주고받는다.

```json
{
  "price": "10000",
  "quantity": "0.5"
}
```

서버 내부에서는 `BigDecimal`로 처리한다.

### 시간

시간은 ISO-8601 문자열로 응답한다.

```text
2026-04-30T12:30:45.123
```

### 공통 에러 응답

```json
{
  "code": "INSUFFICIENT_BALANCE",
  "message": "Insufficient balance"
}
```

대표 에러 코드:

| Code | 의미 |
|---|---|
| `INVALID_REQUEST` | 요청 형식 오류 |
| `UNAUTHORIZED` | 인증 실패 |
| `USER_NOT_FOUND` | 사용자를 찾을 수 없음 |
| `DUPLICATE_EMAIL` | 이미 가입된 email |
| `INVALID_CREDENTIALS` | 로그인 정보 불일치 |
| `MARKET_NOT_FOUND` | 시장을 찾을 수 없음 |
| `MARKET_NOT_ACTIVE` | 거래 불가능한 시장 |
| `MARKET_CANCEL_ONLY` | 취소 전용 시장 (신규 주문 거절) |
| `INVALID_ORDER_TYPE` | 지원하지 않는 주문 유형 |
| `INVALID_ORDER_SIDE` | 지원하지 않는 주문 방향 |
| `INVALID_PRICE` | 유효하지 않은 가격 |
| `INVALID_QUANTITY` | 유효하지 않은 수량 |
| `INVALID_TICK_SIZE` | 가격 단위 불일치 |
| `INVALID_STEP_SIZE` | 수량 단위 불일치 |
| `MIN_ORDER_QUANTITY_NOT_MET` | 최소 주문 수량 미달 |
| `MIN_ORDER_AMOUNT_NOT_MET` | 최소 주문 금액 미달 |
| `INSUFFICIENT_BALANCE` | 잔액 부족 |
| `ORDER_NOT_FOUND` | 주문을 찾을 수 없음 |
| `ORDER_NOT_CANCELABLE` | 취소 불가능한 주문 |
| `SELF_TRADE_NOT_ALLOWED` | 자기 체결 거절 |
| `DUPLICATE_CLIENT_ORDER_ID` | 중복 client order id |

### 에러 코드 상황별 매핑

| 상황 | 에러 코드 |
|---|---|
| request body 형식 오류, 필수 필드 누락 | `INVALID_REQUEST` |
| Authorization 헤더 없음 또는 JWT 유효하지 않음 | `UNAUTHORIZED` |
| JWT subject의 user id가 DB에 없음 | `USER_NOT_FOUND` |
| 회원가입 시 이미 사용 중인 email | `DUPLICATE_EMAIL` |
| 로그인 시 email 없음 또는 password 불일치 | `INVALID_CREDENTIALS` |
| 존재하지 않는 market symbol | `MARKET_NOT_FOUND` |
| market status가 `INACTIVE` 또는 `SUSPENDED`인 시장에 주문 생성 | `MARKET_NOT_ACTIVE` |
| market `cancel_only=true`인 시장에 신규 주문 생성 | `MARKET_CANCEL_ONLY` |
| `type`이 `LIMIT`이 아닌 주문 | `INVALID_ORDER_TYPE` |
| `side`가 `BUY` 또는 `SELL`이 아닌 주문 | `INVALID_ORDER_SIDE` |
| `price <= 0` 또는 `null` | `INVALID_PRICE` |
| `quantity <= 0` 또는 `null` | `INVALID_QUANTITY` |
| price가 market `tick_size`의 배수가 아님 | `INVALID_TICK_SIZE` |
| quantity가 market `step_size`의 배수가 아님 | `INVALID_STEP_SIZE` |
| quantity < market `min_order_quantity` | `MIN_ORDER_QUANTITY_NOT_MET` |
| price × quantity < market `min_order_amount` | `MIN_ORDER_AMOUNT_NOT_MET` |
| taker 잔액 재검증 실패 (wallet row lock 이후) | `INSUFFICIENT_BALANCE` |
| 존재하지 않는 orderId 조회/취소 또는 타인 주문 접근 | `ORDER_NOT_FOUND` |
| `FILLED` 또는 `CANCELED` 주문 취소 시도 | `ORDER_NOT_CANCELABLE` |
| 자기 체결 후보가 하나라도 있는 taker 주문 | `SELF_TRADE_NOT_ALLOWED` |
| 동일 사용자가 같은 `clientOrderId`로 두 번째 주문 | `DUPLICATE_CLIENT_ORDER_ID` |

> 타인 주문을 조회/취소하려 할 때 `ORDER_ACCESS_DENIED` 대신 `ORDER_NOT_FOUND`를 반환한다. 주문 존재 여부를 노출하지 않기 위한 의도적인 정책이다.

## 2. 도메인 처리 규칙

### 2.1 주문 상태 전이

```text
요청 수신
  -> 검증 성공
  -> OPEN
  -> 일부 체결
  -> PARTIALLY_FILLED
  -> 전량 체결
  -> FILLED
```

```text
OPEN 또는 PARTIALLY_FILLED
  -> 사용자 취소
  -> CANCELED
```

검증 실패 주문은 DB에 저장하지 않는다.

```text
INVALID_REQUEST
MARKET_NOT_ACTIVE
INSUFFICIENT_BALANCE
SELF_TRADE_NOT_ALLOWED
...
  -> 주문 저장 안 함
  -> 에러 응답
```

오더북에는 `OPEN`, `PARTIALLY_FILLED` 주문만 존재할 수 있다. `FILLED`, `CANCELED` 주문은 오더북에서 제거되어야 한다.

### 2.2 주문 lock 정책

`lockedAmount`는 주문에 현재 남아 있는 잠금 수량/금액을 의미한다.

| 주문 | lockedAsset | lockedAmount |
|---|---|---|
| `BUY` | quote asset | `price * remainingQuantity`를 market의 `amountScale` 기준으로 CEILING rounding |
| `SELL` | base asset | `remainingQuantity` |

예를 들어 `BTC-KRW` 시장에서 `BUY price=10000, quantity=0.5` 주문을 생성하면 최초 잠금은 `KRW 5000`이다.

이후 `0.2 BTC`가 체결되어 `remainingQuantity=0.3`이 되면 남은 잠금은 `KRW 3000`이다.

BUY 주문은 실제 필요 금액보다 적게 잠기지 않도록 CEILING rounding을 사용한다. 부분 체결 후에는 기존 `lockedAmount`를 단순 차감하지 않고 `price * newRemainingQuantity` 기준으로 다시 계산한다.

`FILLED` 또는 `CANCELED` 상태가 되면 `lockedAmount = 0`이다.

> **용어 구분**: `wallet.locked`(지갑 조회 응답)는 해당 사용자-자산의 전체 잠금 합산이다. `order.lockedAmount`(주문 응답)는 해당 주문 하나에 귀속된 잔여 잠금이다. 두 필드는 합산 관계이지 같은 값이 아니다. 예를 들어 BUY 주문 2건이 각각 KRW 5000, KRW 3000을 잠갔다면 `wallet.locked = 8000`, 각 주문의 `lockedAmount = 5000`, `3000`이다.

### 2.3 cancel_only 정책

`markets.cancel_only = true`인 시장은 신규 주문 생성을 거절한다. 취소 요청은 허용된다.

| market.status | cancel_only | 신규 주문 | 취소 |
|---|---|---|---|
| `ACTIVE` | `false` | 허용 | 허용 |
| `ACTIVE` | `true` | `MARKET_CANCEL_ONLY` 반환 | 허용 |
| `INACTIVE` / `SUSPENDED` | - | `MARKET_NOT_ACTIVE` 반환 | 허용 |

`INACTIVE`, `SUSPENDED` 시장에서도 기존 주문 취소는 허용한다. 취소를 막으면 locked 자산이 해제되지 않아 사용자 자산이 묶이기 때문이다.

### 2.3 체결/정산 규칙

체결 가격은 항상 maker 주문 가격이다.

BUY 주문이 체결되면:

```text
trade_quote_amount:
  trade price * fill quantity를 market의 amountScale 기준으로 DOWN rounding

buyer quote wallet:
  new_locked = buyer order price * new remaining quantity를 amountScale 기준으로 CEILING rounding
  released_amount = old_locked - new_locked
  locked 감소 = released_amount
  available 증가 = released_amount - trade_quote_amount

buyer base wallet:
  available 증가 = fill quantity
```

SELL 주문이 체결되면:

```text
seller base wallet:
  locked 감소 = fill quantity

seller quote wallet:
  available 증가 = trade_quote_amount
```

MVP에서는 수수료를 적용하지 않는다.
rounding 후 `trade_quote_amount`가 0이 되는 체결은 만들지 않는다.

### 2.4 주문 생성 트랜잭션 흐름

주문 생성은 market lock 범위 안에서 DB 변경을 하나의 트랜잭션으로 수행한다. market 조회와 요청값 검증은 트랜잭션 전 사전 검증이고, 주문/체결/정산/원장/이벤트 저장은 같은 트랜잭션에서 처리한다.

```text
1.  JWT에서 currentUserId 추출
2.  market 조회
3.  market status 검증 / cancel_only=true이면 MARKET_CANCEL_ONLY 반환
4.  side/type/timeInForce 검증
5.  price tickSize 검증
6.  quantity stepSize 검증
7.  minOrderQuantity / minOrderAmount 검증
8.  clientOrderId 중복 검증
9.  자기 체결 사전 검증 (교차 가능한 후보 중 userId == currentUserId인 주문이 하나라도 있으면 SELF_TRADE_NOT_ALLOWED 반환, 이후 단계 진행 없음)
10. order sequence 발급
11. taker wallet row lock 및 잔액 재검증
12. taker 자산 lock (available → locked)
13. order 저장
14. ORDER_ACCEPTED domain event 기록
15. ORDER_LOCK wallet ledger 기록
16. 메모리 오더북 후보 기준 매칭 계획 생성 (큐 미변경)
17. maker order row lock 및 상태/수량 재검증
18. 체결에 관련된 buyer/seller wallet을 (user_id, asset) 오름차순으로 lock
19. trade 저장
20. order 수량/상태 갱신 (완전 체결 시 lockedAmount = 0)
21. wallet 정산
22. wallet ledger 기록
23. domain event 기록
24. commit 이후 메모리 오더북 변경 (시장 lock 범위 안에서 완료)
```

### 2.5 주문 취소 트랜잭션 흐름

```text
1. JWT에서 currentUserId 추출
2. order row lock
3. 주문 소유자 검증
4. 주문 상태 검증
5. wallet row lock (lockedAsset wallet을 (user_id, asset) 오름차순으로 잠금)
6. remainingQuantity 기준 잔여 locked 해제
7. order 상태 CANCELED 변경 및 lockedAmount = 0
8. wallet ledger 기록
9. domain event 기록
10. commit 이후 메모리 오더북에서 제거 (시장 lock 범위 안에서 완료)
```

### 2.6 메모리 오더북 초기화

메모리 오더북은 DB의 파생 조회 모델이다. DB가 source of truth이다.

서버 시작 시 DB에서 `OPEN`, `PARTIALLY_FILLED` 주문을 조회하여 메모리 오더북을 초기화한다.

초기화 대상:

```text
orders.status IN ('OPEN', 'PARTIALLY_FILLED')
```

초기화 제외 대상:

```text
FILLED
CANCELED
```

## 3. Auth

### 3.1 회원가입

```http
POST /api/v1/auth/signup
```

인증: 불필요

Request:

```json
{
  "email": "user1@example.com",
  "password": "password1234",
  "nickname": "user1"
}
```

Response:

```json
{
  "userId": 1,
  "email": "user1@example.com",
  "nickname": "user1",
  "status": "ACTIVE",
  "createdAt": "2026-04-30T12:30:45.123"
}
```

### 3.2 로그인

```http
POST /api/v1/auth/login
```

인증: 불필요

Request:

```json
{
  "email": "user1@example.com",
  "password": "password1234"
}
```

Response:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "userId": 1,
    "email": "user1@example.com",
    "nickname": "user1",
    "status": "ACTIVE"
  }
}
```

### 3.3 현재 사용자 조회

```http
GET /api/v1/users/me
```

인증: 필요

Response:

```json
{
  "userId": 1,
  "email": "user1@example.com",
  "nickname": "user1",
  "status": "ACTIVE",
  "createdAt": "2026-04-30T12:30:45.123"
}
```

## 4. Markets

### 4.1 시장 목록 조회

```http
GET /api/v1/markets
```

인증: 불필요

Response:

```json
[
  {
    "market": "BTC-KRW",
    "displayName": "BTC/KRW",
    "baseAsset": "BTC",
    "quoteAsset": "KRW",
    "amountScale": 0,
    "tickSize": "1",
    "stepSize": "0.00000001",
    "minOrderQuantity": "0.0001",
    "minOrderAmount": "5000",
    "status": "ACTIVE",
    "cancelOnly": false
  }
]
```

## 5. Wallets

### 5.1 지갑 조회

```http
GET /api/v1/wallets
```

인증: 필요

Response:

```json
[
  {
    "walletId": 1,
    "asset": "KRW",
    "availableBalance": "1000000",
    "lockedBalance": "0"
  },
  {
    "walletId": 2,
    "asset": "BTC",
    "availableBalance": "1.5",
    "lockedBalance": "0"
  }
]
```

### 5.2 원장 조회

```http
GET /api/v1/wallets/ledgers?asset=KRW&limit=50
```

인증: 필요

Query:

| Name | Required | 설명 |
|---|---|---|
| `asset` | N | 자산 코드 |
| `limit` | N | 조회 개수, 기본값 50 |

Response:

```json
[
  {
    "ledgerId": 1,
    "asset": "KRW",
    "type": "ORDER_LOCK",
    "deltaAvailable": "-5000",
    "deltaLocked": "5000",
    "availableBalanceAfter": "995000",
    "lockedBalanceAfter": "5000",
    "orderId": 1001,
    "tradeId": null,
    "createdAt": "2026-04-30T12:31:10.123"
  }
]
```

`referenceType`, `referenceId`는 저장/응답하지 않는다. 참조 대상은 `orderId`, `tradeId`로 구분한다.

### 5.3 개발용 입금 보조 API

```http
POST /api/v1/wallets/deposit
```

인증: 필요

프로필: `!prod`

이 API는 로컬 개발과 테스트 데이터 준비를 위한 보조 기능이다. MVP의 운영 입금/출금 기능이 아니며, `prod` 프로필에서는 컨트롤러가 등록되지 않는다.

Request:

```json
{
  "asset": "KRW",
  "amount": "1000000"
}
```

Response:

```json
{
  "walletId": 1,
  "asset": "KRW",
  "availableBalance": "1000000",
  "lockedBalance": "0"
}
```

## 6. Orders

### 6.1 주문 생성

```http
POST /api/v1/orders
```

인증: 필요

Request:

```json
{
  "market": "BTC-KRW",
  "side": "BUY",
  "type": "LIMIT",
  "timeInForce": "GTC",
  "price": "10000",
  "quantity": "0.5",
  "clientOrderId": "user1-order-001"
}
```

Response:

```json
{
  "orderId": 1001,
  "clientOrderId": "user1-order-001",
  "market": "BTC-KRW",
  "side": "BUY",
  "type": "LIMIT",
  "timeInForce": "GTC",
  "price": "10000",
  "originalQuantity": "0.5",
  "executedQuantity": "0.2",
  "remainingQuantity": "0.3",
  "executedQuoteAmount": "1980",
  "lockedAsset": "KRW",
  "lockedAmount": "3000",
  "status": "PARTIALLY_FILLED",
  "createdAt": "2026-04-30T12:31:10.123",
  "trades": [
    {
      "tradeId": 501,
      "price": "9900",
      "quantity": "0.2",
      "quoteAmount": "1980",
      "liquidity": "TAKER",
      "tradedAt": "2026-04-30T12:31:10.123"
    }
  ]
}
```

규칙:

- `userId`는 요청에 포함하지 않는다.
- 현재 사용자 ID는 JWT에서 추출한다.
- MVP에서는 `type=LIMIT`, `timeInForce=GTC`만 허용한다.
- `lockedAmount`는 현재 주문 잔량에 대해 남아 있는 잠금 수량/금액이다.
- 주문 생성 성공은 matching/settlement 처리 후 확정된 상태를 반환한다.
- 체결이 발생하지 않으면 `trades`는 빈 배열이다.
- 주문 생성 응답의 `trades[].liquidity`는 생성된 주문 기준으로 `MAKER` 또는 `TAKER`를 반환한다. 일반적으로 새 주문은 taker로 매칭된다.
- `clientOrderId`는 optional이다. 값이 있으면 동일 사용자 내 중복을 거절한다(`DUPLICATE_CLIENT_ORDER_ID`). 값이 없으면 멱등성을 보장하지 않으며, null 주문 여러 건을 허용한다.

비동기 주문 접수 응답 모델:

현재 `POST /api/v1/orders`는 기존 `201 Created` 동기 응답을 유지한다. 비동기 전환 시에는 주문 접수 결과만 반환하고, 체결 결과는 주문 조회 API와 WebSocket feed로 확인한다.

Status:

```http
202 Accepted
```

Response:

```json
{
  "orderId": 1001,
  "clientOrderId": "user1-order-001",
  "market": "BTC-KRW",
  "side": "BUY",
  "status": "ACCEPTED",
  "acceptedAt": "2026-05-28T12:31:10.123"
}
```

계약:

- `trades` 필드 제외
- 체결 결과는 `GET /api/v1/orders/{orderId}`와 `/topic/trades/{market}` 기준
- `ACCEPTED`, `REJECTED` 상태는 비동기 주문 처리 전환 범위에서 사용
- 실제 `202 Accepted` 전환은 주문 접수 transaction 분리 이후 적용

### 6.2 주문 취소

```http
POST /api/v1/orders/{orderId}/cancel
```

인증: 필요

Request body 없음.

Response:

```json
{
  "orderId": 1001,
  "market": "BTC-KRW",
  "status": "CANCELED",
  "releasedAsset": "KRW",
  "releasedAmount": "3000",
  "canceledAt": "2026-04-30T12:35:00.123"
}
```

규칙:

- 현재 로그인 사용자의 주문만 취소할 수 있다.
- `OPEN`, `PARTIALLY_FILLED` 상태만 취소 가능하다.
- 취소 시 잔여 수량에 해당하는 locked asset을 available로 되돌린다.
- `releasedAmount`는 취소로 해제된 잔여 lock 수량/금액이다.

`releasedAmount` 계산:

```text
releasedAsset  = order.lockedAsset
releasedAmount = order.lockedAmount

wallet.locked    -= releasedAmount
wallet.available += releasedAmount
order.lockedAmount = 0
order.status       = CANCELED
```

BUY 주문은 부분 체결 시마다 `lockedAmount`를 `price * remainingQuantity` CEILING으로 재계산해 유지하므로, 취소 시 `price * remainingQuantity`를 다시 계산하지 않고 저장된 `order.lockedAmount`를 그대로 사용한다. SELL 주문도 동일하게 `lockedAmount == remainingQuantity`이므로 같은 방식으로 처리한다.

### 6.3 주문 단건 조회

```http
GET /api/v1/orders/{orderId}
```

인증: 필요

Response:

```json
{
  "orderId": 1001,
  "clientOrderId": "user1-order-001",
  "market": "BTC-KRW",
  "side": "BUY",
  "type": "LIMIT",
  "timeInForce": "GTC",
  "price": "10000",
  "originalQuantity": "0.5",
  "executedQuantity": "0.2",
  "remainingQuantity": "0.3",
  "executedQuoteAmount": "1980",
  "lockedAsset": "KRW",
  "lockedAmount": "3000",
  "status": "PARTIALLY_FILLED",
  "createdAt": "2026-04-30T12:31:10.123",
  "closedAt": null
}
```

### 6.4 주문 목록 조회

```http
GET /api/v1/orders?market=BTC-KRW&limit=50&offset=0
```

인증: 필요

Query:

| Name | Required | 설명 |
|---|---|---|
| `market` | N | 시장 심볼 |
| `limit` | N | 조회 개수, 기본값 50 |
| `offset` | N | 건너뛸 개수, 기본값 0 |

Response:

```json
[
  {
    "orderId": 1001,
    "clientOrderId": "user1-order-001",
    "market": "BTC-KRW",
    "side": "BUY",
    "price": "10000",
    "originalQuantity": "0.5",
    "executedQuantity": "0.2",
    "remainingQuantity": "0.3",
    "status": "PARTIALLY_FILLED",
    "createdAt": "2026-04-30T12:31:10.123"
  }
]
```

## 7. OrderBook

### 7.1 오더북 조회

```http
GET /api/v1/markets/{market}/orderbook?depth=10
```

인증: 불필요

Query:

| Name | Required | 설명 |
|---|---|---|
| `depth` | N | 가격 레벨 개수, 기본값 10 |

Response:

```json
{
  "market": "BTC-KRW",
  "bids": [
    {
      "price": "10000",
      "quantity": "1.2"
    }
  ],
  "asks": [
    {
      "price": "10100",
      "quantity": "0.4"
    }
  ]
}
```

규칙:

- 오더북은 메모리 오더북 기반 조회 모델이다.
- `bids`는 가격 내림차순이다.
- `asks`는 가격 오름차순이다.
- 같은 가격 주문은 합산 수량으로 응답한다.

## 8. Trades / Fills

### 8.1 시장 최근 체결 조회

```http
GET /api/v1/markets/{market}/trades?limit=50
```

인증: 불필요

Response:

```json
[
  {
    "tradeId": 501,
    "market": "BTC-KRW",
    "price": "9900",
    "quantity": "0.2",
    "quoteAmount": "1980",
    "tradedAt": "2026-04-30T12:31:10.123"
  }
]
```

### 8.2 사용자 fill 조회

```http
GET /api/v1/fills?market=BTC-KRW&orderId=1001&lastFillId=0&limit=50
```

인증: 필요

Query:

| Name | Required | 설명 |
|---|---|---|
| `market` | N | 시장 심볼 |
| `orderId` | N | 특정 주문의 fill만 조회 |
| `lastFillId` | N | 이 ID보다 큰 fill만 조회, 기본값 0 |
| `limit` | N | 조회 개수, 기본값 50 |

Response:

```json
[
  {
    "tradeId": 501,
    "orderId": 1001,
    "market": "BTC-KRW",
    "side": "BUY",
    "liquidity": "T",
    "price": "9900",
    "quantity": "0.2",
    "quoteAmount": "1980",
    "settled": true,
    "tradedAt": "2026-04-30T12:31:10.123"
  }
]
```

규칙:

- fill은 별도 테이블에 저장하지 않고 `trades`에서 로그인 사용자 기준으로 파생 조회한다.
- 요청 사용자의 주문이 `maker_order_id`와 같으면 `liquidity=M`이다.
- 요청 사용자의 주문이 `taker_order_id`와 같으면 `liquidity=T`이다.
- 주문/체결/정산이 같은 트랜잭션에서 완료되므로 `settled=true`로 응답한다.
- `orderId` 필터 사용 시: 해당 주문이 존재하지 않거나 현재 사용자의 주문이 아니면 `ORDER_NOT_FOUND`를 반환한다. 본인 주문이지만 fill이 없으면 빈 배열을 반환한다.
