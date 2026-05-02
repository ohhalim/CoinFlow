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
| `BUY` | quote asset | `price * remainingQuantity` |
| `SELL` | base asset | `remainingQuantity` |

예를 들어 `BTC-KRW` 시장에서 `BUY price=10000, quantity=0.5` 주문을 생성하면 최초 잠금은 `KRW 5000`이다.

이후 `0.2 BTC`가 체결되어 `remainingQuantity=0.3`이 되면 남은 잠금은 `KRW 3000`이다.

### 2.3 체결/정산 규칙

체결 가격은 항상 maker 주문 가격이다.

BUY 주문이 체결되면:

```text
buyer quote wallet:
  locked 감소 = buyer order price * fill quantity
  available 증가 = (buyer order price - trade price) * fill quantity

buyer base wallet:
  available 증가 = fill quantity
```

SELL 주문이 체결되면:

```text
seller base wallet:
  locked 감소 = fill quantity

seller quote wallet:
  available 증가 = trade price * fill quantity
```

MVP에서는 수수료를 적용하지 않는다.

### 2.4 주문 생성 트랜잭션 흐름

주문 생성은 아래 처리를 하나의 트랜잭션 경계 안에서 수행한다.

```text
1. JWT에서 currentUserId 추출
2. market 조회
3. market status 검증
4. side/type/timeInForce 검증
5. price tickSize 검증
6. quantity stepSize 검증
7. minOrderQuantity / minOrderAmount 검증
8. clientOrderId 중복 검증
9. order sequence 발급
10. wallet row lock
11. 자산 lock
12. order 저장
13. 메모리 오더북 후보 기준 매칭 계획 생성
14. maker order row lock 및 상태 재검증
15. trade 저장
16. order 수량/상태 갱신
17. wallet 정산
18. wallet ledger 기록
19. domain event 기록
20. commit 이후 메모리 오더북 변경
```

### 2.5 주문 취소 트랜잭션 흐름

```text
1. JWT에서 currentUserId 추출
2. order row lock
3. 주문 소유자 검증
4. 주문 상태 검증
5. wallet row lock
6. remainingQuantity 기준 잔여 locked 해제
7. order 상태 CANCELED 변경
8. wallet ledger 기록
9. domain event 기록
10. commit 이후 메모리 오더북에서 제거
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
    "asset": "KRW",
    "available": "1000000",
    "locked": "0"
  },
  {
    "asset": "BTC",
    "available": "1.5",
    "locked": "0"
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
    "availableAfter": "995000",
    "lockedAfter": "5000",
    "referenceType": "ORDER",
    "referenceId": 1001,
    "orderId": 1001,
    "tradeId": null,
    "createdAt": "2026-04-30T12:31:10.123"
  }
]
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
      "liquidity": "T",
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
  "closedAt": null,
  "closedReason": null
}
```

### 6.4 주문 목록 조회

```http
GET /api/v1/orders?market=BTC-KRW&status=OPEN&limit=50
```

인증: 필요

Query:

| Name | Required | 설명 |
|---|---|---|
| `market` | N | 시장 심볼 |
| `status` | N | 주문 상태 |
| `limit` | N | 조회 개수, 기본값 50 |

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
    "buyOrderId": 1001,
    "sellOrderId": 900,
    "makerOrderId": 900,
    "takerOrderId": 1001,
    "tradedAt": "2026-04-30T12:31:10.123"
  }
]
```

### 8.2 사용자 fill 조회

```http
GET /api/v1/fills?market=BTC-KRW&orderId=1001&limit=50
```

인증: 필요

Query:

| Name | Required | 설명 |
|---|---|---|
| `market` | N | 시장 심볼 |
| `orderId` | N | 특정 주문의 fill만 조회 |
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
