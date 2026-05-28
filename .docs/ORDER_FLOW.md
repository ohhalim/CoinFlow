# CoinFlow 주문 처리 흐름

이 문서는 CoinFlow에서 주문 하나가 생성되고 체결, 정산, 원장 기록, 오더북 반영으로 이어지는 내부 처리 흐름을 설명합니다.

현재 문서는 동기 주문 생성 흐름 기준입니다. 주문 접수 응답과 체결/정산 처리를 분리하는 후속 설계는 [ASYNC_ORDER_ACCEPTANCE.md](./design/ASYNC_ORDER_ACCEPTANCE.md)를 기준으로 합니다.

## 1. 전체 흐름

```text
POST /api/v1/orders
  -> JWT에서 userId 추출
  -> market 조회 및 주문 정책 검증
  -> 주문 방향에 따라 잠글 자산 계산
  -> market별 ReentrantLock 획득
  -> DB transaction 시작
  -> clientOrderId 중복 확인
  -> self-trade 후보 확인
  -> market별 sequence 발급
  -> wallet row pessimistic lock
  -> available -> locked 이동
  -> order 저장
  -> ORDER_ACCEPTED 이벤트 저장
  -> ORDER_LOCK 원장 저장
  -> 인메모리 오더북으로 매칭 계획 수립
  -> DB 기준 체결/정산/원장/이벤트 저장
  -> transaction commit
  -> afterCommit에서 인메모리 오더북 반영
  -> 응답 반환
```

핵심은 DB 상태를 먼저 확정하고, 인메모리 오더북은 commit 이후에만 변경한다는 점입니다. DB는 source of truth이고, 오더북은 매칭과 조회를 위한 파생 상태입니다.

## 2. 주문 검증

`OrderService.createOrder()`는 주문 저장 전에 시장과 요청값을 검증합니다.

- market symbol 존재 여부
- market active 여부
- cancelOnly 여부
- side: `BUY` 또는 `SELL`
- type: 현재는 `LIMIT`
- timeInForce: 현재는 `GTC`
- price > 0
- quantity > 0
- price가 `tickSize` 단위에 맞는지
- quantity가 `stepSize` 단위에 맞는지
- 최소 주문 수량
- 최소 주문 금액

BTC-KRW seed 기준:

- base asset: `BTC`
- quote asset: `KRW`
- tick size: `1`
- step size: `0.00000001`
- min order quantity: `0.0001`
- min order amount: `5000`
- amount scale: `0`

## 3. 자산 잠금

주문이 오더북에 올라가면 해당 자산은 체결 또는 취소 전까지 다른 주문에 다시 사용되면 안 됩니다. 그래서 주문 생성 시 필요한 자산을 `available`에서 `locked`로 이동시킵니다.

BUY 주문:

```text
lockedAsset = quoteAsset
lockedAmount = price * quantity를 amountScale 기준으로 CEILING
```

예:

```text
BUY BTC-KRW price=100000000 quantity=0.0001
lockedAsset = KRW
lockedAmount = 10000
```

SELL 주문:

```text
lockedAsset = baseAsset
lockedAmount = quantity
```

예:

```text
SELL BTC-KRW price=100000000 quantity=0.0001
lockedAsset = BTC
lockedAmount = 0.0001
```

## 4. Lock 전략

### 4.1 market별 ReentrantLock

`OrderService`는 market id별 `ReentrantLock`을 관리합니다.

```text
Map<Long, ReentrantLock> marketLocks
```

같은 market id의 주문 생성/취소는 하나씩 처리되고, 다른 market은 서로 막지 않습니다.

목적:

- 같은 시장의 가격-시간 우선순위 보장
- 인메모리 오더북 변경 순서 보장
- commit 이후 오더북 반영 전 다음 주문이 stale state를 보지 않도록 방지

한계:

- 단일 JVM 안에서만 유효합니다.
- 서버가 여러 대가 되면 분산 lock, DB queue, Kafka partition 기반 market worker 같은 구조가 필요합니다.

### 4.2 DB pessimistic lock

DB에서는 다음 row에 pessimistic write lock을 사용합니다.

- `order_sequences`: 시장별 sequence 발급
- `wallets`: 주문 자산 잠금과 체결 정산
- `orders`: maker 주문 체결, 취소 대상 주문

동시에 같은 지갑이나 주문을 수정할 때 lost update를 막기 위한 선택입니다.

### 4.3 지갑 lock 순서 정렬

체결 정산에서는 buyer/seller의 여러 지갑을 동시에 수정합니다.

- buyer base wallet
- buyer quote wallet
- seller base wallet
- seller quote wallet

`lockWalletsInOrder()`는 `(userId, asset)` 기준으로 정렬해 항상 같은 순서로 지갑 row lock을 잡습니다. 서로 다른 트랜잭션이 다른 순서로 lock을 잡을 때 생길 수 있는 deadlock 위험을 낮추기 위한 처리입니다.

## 5. 매칭 알고리즘

`MemoryOrderBook`은 BUY queue와 SELL queue를 갖습니다.

BUY queue:

```text
높은 가격 우선
같은 가격이면 낮은 sequence 우선
```

SELL queue:

```text
낮은 가격 우선
같은 가격이면 낮은 sequence 우선
```

BUY taker가 들어오면 SELL queue를 보고, 아래 조건이면 체결 가능합니다.

```text
taker.price >= maker.price
```

SELL taker가 들어오면 BUY queue를 보고, 아래 조건이면 체결 가능합니다.

```text
taker.price <= maker.price
```

체결 가격은 항상 maker 주문 가격입니다.

```text
SELL maker: 98,000,000
BUY taker: 100,000,000
체결 가격: 98,000,000
```

`planMatch()`는 실제 queue를 바꾸지 않고 simulation queue로 체결 계획만 만듭니다. DB transaction이 실패할 수 있으므로, 실제 오더북 변경은 commit 이후 `applyMatchPlan()`에서 수행합니다.

## 6. 자기 체결 방지

현재 정책은 보수적입니다.

```text
가격이 교차되는 후보 중 같은 userId의 maker 주문이 하나라도 있으면 taker 주문 전체 거절
```

실제 매칭 순서상 먼저 타인의 주문과 체결될 수 있더라도, 가격 범위 안에 자기 주문이 있으면 전체 주문을 거절합니다. MVP에서는 복잡한 self-trade prevention 모드 대신 구현이 단순하고 자전거래 가능성을 강하게 차단하는 정책을 선택했습니다.

## 7. 체결 정산

`settle()`은 매칭 계획을 DB 상태에 반영합니다.

각 `MatchResult`마다 다음 처리를 수행합니다.

1. maker order를 DB lock으로 조회
2. maker/taker order의 체결 수량 반영
3. buyer/seller 지갑 lock
4. buyer quote locked 차감
5. maker 가격 차이로 발생한 buyer refund 처리
6. buyer base available 증가
7. seller base locked 감소
8. seller quote available 증가
9. trade 저장
10. order fill 이벤트 저장
11. trade created 이벤트 저장
12. wallet ledger 4종 저장
13. dust maker 자동 취소 검사
14. settlement completed 이벤트 저장

### 7.1 BUY 정산 예시

```text
SELL maker price=98,000,000 quantity=0.0001
BUY taker price=100,000,000 quantity=0.0001
```

BUY taker는 주문 생성 시 KRW 10,000을 잠급니다.

실제 체결 금액:

```text
98,000,000 * 0.0001 = 9,800
```

정산 결과:

```text
buyer KRW locked: 10,000 -> 0
buyer KRW available: +200 refund
buyer BTC available: +0.0001
seller BTC locked: -0.0001
seller KRW available: +9,800
```

### 7.2 SELL 정산 예시

```text
BUY maker price=100,000,000 quantity=0.0001
SELL taker price=100,000,000 quantity=0.0001
```

정산 결과:

```text
buyer KRW locked: -10,000
buyer BTC available: +0.0001
seller BTC locked: -0.0001
seller KRW available: +10,000
```

## 8. 원장 기록

지갑 변경마다 `WalletLedger`를 저장합니다.

주문 생성:

```text
ORDER_LOCK
```

주문 취소:

```text
ORDER_CANCEL_RELEASE
```

체결:

```text
TRADE_BUY_QUOTE_SETTLE
TRADE_BUY_BASE_CREDIT
TRADE_SELL_BASE_SETTLE
TRADE_SELL_QUOTE_CREDIT
```

원장에는 다음 정보가 저장됩니다.

- user id
- wallet id
- asset
- ledger type
- available delta
- locked delta
- 변경 후 available balance
- 변경 후 locked balance
- 관련 order id
- 관련 trade id

`wallets`는 현재 잔액 스냅샷이고, `wallet_ledgers`는 잔액 변경 감사 로그입니다.

## 9. 도메인 이벤트

주요 상태 변화마다 `DomainEventRecorder`가 이벤트를 저장합니다.

이벤트 종류:

- `ORDER_ACCEPTED`
- `ORDER_PARTIALLY_FILLED`
- `ORDER_FILLED`
- `ORDER_CANCELED`
- `TRADE_CREATED`
- `SETTLEMENT_COMPLETED`

현재는 DB에 저장만 합니다. 다음 단계에서는 아래 흐름으로 확장할 수 있습니다.

```text
domain_events(published=false)
  -> OutboxPublisher polling
  -> Kafka
  -> WebSocketBroadcaster
```

## 10. Commit 이후 오더북 반영

`OrderService`는 `TransactionSynchronizationManager.registerSynchronization()`을 사용합니다.

DB transaction이 commit되면:

```text
matchingEngine.applyMatchPlan(market, order, plan)
autoCanceledMakers -> matchingEngine.cancelOrder()
```

transaction 안에서 오더북을 바로 바꾸지 않는 이유는 DB rollback과 인메모리 queue rollback의 경계가 다르기 때문입니다. DB transaction이 rollback되면 DB 상태는 되돌아가지만, 인메모리 queue 변경은 자동으로 되돌아가지 않습니다.

오더북 반영 실패 시:

```text
OrderBookRecoveryService.rebuildAfterApplyFailure(marketId)
  -> DB에서 OPEN/PARTIALLY_FILLED 주문 조회
  -> sequence 순서로 오더북 재빌드
  -> 재빌드 실패 시 market cancelOnly 전환
```

## 11. 주문 취소 흐름

```text
POST /api/v1/orders/{id}/cancel
  -> 사용자 소유 주문 조회
  -> 취소 가능 상태인지 확인
  -> market별 lock 획득
  -> DB transaction 시작
  -> order row lock
  -> wallet row lock
  -> order.lockedAmount만큼 unlock
  -> ORDER_CANCEL_RELEASE 원장 저장
  -> order status CANCELED
  -> ORDER_CANCELED 이벤트 저장
  -> commit
  -> afterCommit에서 인메모리 오더북에서 제거
```

취소 가능한 상태:

- `OPEN`
- `PARTIALLY_FILLED`

취소 불가능한 상태:

- `FILLED`
- `CANCELED`

## 12. 서버 시작 시 오더북 초기화

`OrderBookInitializer`는 application start 시 실행됩니다.

```text
ACTIVE market 조회
OPEN/PARTIALLY_FILLED order 조회
sequence ASC 순서로 MatchingEngine에 add
```

서버가 재시작되어도 DB에 남은 미체결 주문으로 인메모리 오더북을 다시 만들 수 있습니다.

## 13. 요약

CoinFlow의 주문 처리는 시장 정책 검증, 자산 잠금, 매칭 계획 수립, DB 정산, commit 이후 오더북 반영 순서로 진행됩니다. DB는 주문, 체결, 지갑, 원장, 도메인 이벤트의 source of truth이며, 인메모리 오더북은 매칭과 조회를 위한 파생 상태로 관리됩니다. 같은 시장의 주문은 market별 lock으로 직렬화하고, 지갑과 주문 갱신은 pessimistic lock으로 보호합니다.
