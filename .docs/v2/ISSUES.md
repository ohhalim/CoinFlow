# CoinFlow Phase 1 Known Issues & Phase 2 개선 사항

Phase 1 코드 리뷰에서 발견된 이슈를 심각도 순으로 정리한다.  
Phase 2 구현 전에 반드시 수정해야 하는 항목은 **[BLOCKER]**, 개선 권장은 **[IMPROVE]** 로 표시한다.

---

## Priority 1 — 오더북 Rollback 불일치 [BLOCKER]

### 현상

`OrderService.createOrder()`는 `transactionTemplate.execute()` 블록 안에서  
`matchingEngine.match()`를 호출해 in-memory 오더북을 즉시 변경한다.

```
// OrderService.java:120
transactionTemplate.execute(status -> {
    ...
    List<MatchResult> matchResults = matchingEngine.match(market, order);  // 오더북 변경
    List<Trade> trades = settle(market, order, matchResults);               // DB 저장
    ...
});
```

`settle()` 도중 예외가 발생하면 DB는 롤백되지만  
`MemoryOrderBook`(PriorityQueue)은 이미 변경된 상태로 남는다.

### 원인

`MemoryOrderBook.match()` ([MemoryOrderBook.java:33](../../../src/main/java/com/coinflow/order/matching/MemoryOrderBook.java))는  
결과를 계산하는 동시에 `makerQueue.poll()` / `makerQueue.add()` 로 큐를 직접 변경한다.  
DB 트랜잭션 롤백이 in-memory 상태를 되돌리지 못한다.

### 해결 방향 — 계획/반영 2단계 분리

`match()`를 두 단계로 쪼개야 한다.

**1단계 — `planMatch()`: 큐 변경 없이 결과만 계산**

```java
// MemoryOrderBook에 추가
public List<MatchResult> planMatch(Order taker) {
    // makerQueue를 peek만 하고 poll/add하지 않는다
    // 결과 MatchResult 리스트만 반환
}
```

**2단계 — `applyMatchPlan()`: commit 이후 큐 반영**

```java
// OrderService.createOrder() 안에서
List<MatchResult> plan = matchingEngine.planMatch(market, order);

transactionTemplate.execute(status -> {
    List<Trade> trades = settle(market, order, plan);  // DB 저장
    ...
});

// 트랜잭션 커밋 성공 후
matchingEngine.applyMatchPlan(market, order, plan);    // 오더북 반영
```

`TransactionSynchronizationManager.registerSynchronization()` 의 `afterCommit()` 훅으로  
`applyMatchPlan()` 호출을 트랜잭션 커밋 이후로 보장한다.

> **주의**: `afterCommit()` 안에서 예외가 발생해도 트랜잭션은 이미 커밋 완료 상태다.  
> 오더북 반영 실패 시 서버를 재시작하거나 DB 체결 내역에서 오더북을 재구성해야 한다.  
> 로그/알림으로 즉시 감지할 수 있도록 처리해야 한다.

---

## Priority 2 — PriorityQueue 동시 읽기 위험 [BLOCKER]

### 현상

`MemoryOrderBook`의 `getBuySide()` / `getSellSide()` ([MemoryOrderBook.java:113-125](../../../src/main/java/com/coinflow/order/matching/MemoryOrderBook.java))는  
`buyQueue` / `sellQueue`를 `stream()`으로 순회한다.

```java
public List<OrderBookEntry> getBuySide() {
    return buyQueue.stream()  // PriorityQueue iteration — not thread-safe
            .sorted(...)
            .toList();
}
```

`createOrder()` → `match()` 가 큐를 `poll/add` 하는 동안  
REST 오더북 조회(`GET /api/v1/markets/{symbol}/orderbook`)가 `getBuySide()`를 호출하면  
`ConcurrentModificationException` 이 발생한다.

### 원인 — lock 소유자는 이미 OrderService

`OrderService`에는 이미 시장 단위 `ReentrantLock` ([OrderService.java:57](../../../src/main/java/com/coinflow/order/service/OrderService.java))이 있다.  
`match()` 는 이 lock 안에서 호출되므로 동시 체결 사이의 충돌은 없다.

문제는 스냅샷 조회 경로다.  
오더북 REST 엔드포인트는 `marketLock`을 잡지 않은 채 `getBuySide()`를 호출한다.

### 해결 방향

lock 소유자를 `OrderService` 하나로 유지하고,  
`getBuySide()` / `getSellSide()` 호출도 같은 `marketLock` 안에서 실행한다.

```java
// MarketController 또는 MarketService에서 오더북 조회 시
ReentrantLock lock = orderService.getMarketLock(marketId);
lock.lock();
try {
    List<OrderBookEntry> buySide  = matchingEngine.getBuySide(symbol);
    List<OrderBookEntry> sellSide = matchingEngine.getSellSide(symbol);
    ...
} finally {
    lock.unlock();
}
```

`MatchingEngine` 에 lock을 또 추가하면 lock 소유자가 두 군데가 되어  
책임과 순서가 흩어진다. 기존 `marketLock` 을 재사용하는 방향이 맞다.

---

## Priority 3 — Maker Order Row Lock 누락 [BLOCKER]

### 현상

`settle()` ([OrderService.java:227](../../../src/main/java/com/coinflow/order/service/OrderService.java))은 maker 주문을 아래와 같이 조회한다.

```java
Order maker = orderRepository.findById(result.makerOrderId()).orElseThrow();
```

`findById()`는 `SELECT ... FOR UPDATE` 없는 일반 조회다.

### 실제 위험 범위

현재 `createOrder()` / `cancelOrder()` 는 모두 시장 단위 `marketLock` 안에서 실행되므로  
같은 시장 내 동시 접근은 이미 직렬화된다. 즉 현재 구현에서 race가 터지진 않는다.

그러나 **방어 심층(defense in depth)** 관점에서 문제가 있다.

- 향후 "사용자 전체 주문 일괄 취소" 같은 API가 시장 lock을 우회하면 즉시 위험해진다.
- maker 주문의 상태(`OPEN` → `FILLED`) 전환 전에 취소 요청이 끼어들 경우, `isCancelable()` 재검증이 없으면 이미 체결된 주문이 취소될 수 있다.

### 해결 방향

```java
// findById → SELECT FOR UPDATE 로 교체
Order maker = orderRepository.findByIdWithLock(result.makerOrderId()).orElseThrow();

// fill 이전에 상태 재검증
if (!maker.isOpen() && !maker.isPartiallyFilled()) {
    throw new ApiException(ErrorCode.ORDER_NOT_FOUND);
}
```

wallet의 `findByUserIdAndAssetWithLock()` 와 동일한 패턴으로 통일한다.

---

## Priority 4 — DepositRequest 검증 없음 [BLOCKER]

### 현상

```java
// DepositRequest.java
public record DepositRequest(
    String asset,   // @NotNull 없음
    String amount   // @NotNull 없음, String 타입
) {}
```

`asset` 또는 `amount` 가 null / 빈 문자열로 들어오면  
서비스 레이어에서 `new BigDecimal(amount)` 호출 시 `NumberFormatException` 또는 NPE 발생.

### 해결

```java
public record DepositRequest(
    @NotBlank String asset,
    @NotBlank
    @Pattern(regexp = "^\\d+(\\.\\d+)?$", message = "amount must be a positive number")
    String amount
) {}
```

서비스에서 `BigDecimal` 변환 시 이미 검증된 문자열이 들어오도록 보장한다.

### 관련 — 입금 API 범위

`WalletController.deposit()`이 지갑 잔고를 직접 증가시킨다.  
실제 거래소에서 입금은 외부 블록체인 확인 이벤트로 트리거되어야 한다.  
Phase 1 MVP 범위로는 허용하지만, 이후 외부 연동 시 `DepositConfirmationService` 로 분리한다.

---

## Priority 5 — Outbox 보강 [IMPROVE]

### 5-1. send().get() Timeout 없음

```java
// 현재 — Kafka 장애 시 무한 대기
kafkaTemplate.send(topic, key, value).get();

// 수정
kafkaTemplate.send(topic, key, value).get(5, TimeUnit.SECONDS);
```

`TimeoutException` / `ExecutionException` 을 잡아 `publish_attempts` 를 증가시킨다.

### 5-2. Domain Event 페이로드에 스키마/버전 없음

```json
// 현재
{"orderId": 1, "status": "FILLED"}

// 개선 — envelope 구조
{
  "schemaVersion": "1.0",
  "occurredAt": "2024-01-15T10:00:00Z",
  "payload": {"orderId": 1, "status": "FILLED"}
}
```

`occurredAt` 을 DB 저장 시각과 분리하면 감사 추적이 정확해진다.  
`schemaVersion` 으로 Consumer가 역직렬화 전략을 선택할 수 있다.

### 5-3. Outbox 발행 순서

`@Scheduled` polling 쿼리에 `ORDER BY id ASC` 가 없으면  
DB insert 순서와 다른 순서로 이벤트가 발행될 수 있다.

**Kafka partition key(marketSymbol)** 는 producer 발행 이후 파티션 내 순서만 보장한다.  
DB polling 조회 순서와는 무관하다. `ORDER BY id ASC` 는 polling 쿼리에 반드시 추가한다.

멀티 publisher 환경까지 고려하면 `SELECT ... SKIP LOCKED` 로 row claim을 구현해야 한다.

### 5-4. 중복 Consumer 처리 (dedup)

ACK 수신 후 `markPublished()` 커밋 전에 서버가 죽으면 같은 이벤트가 재발행된다.  
WebSocket broadcast는 UX 허용 범위지만  
DB에 결과를 저장하는 Consumer를 추가할 때는 `processed_events` 로 dedup을 구현한다.

```sql
CREATE TABLE processed_events (
    event_id     BIGINT      NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at DATETIME(6),
    PRIMARY KEY (event_id, consumer)
);
```

### 5-5. WebSocket broadcast payload에 dedup key 없음

체결 피드 메시지에 `tradeId` 또는 `eventId` 가 없으면  
클라이언트가 중복 수신 여부를 판별할 수 없다.

```json
{
  "tradeId": 42,
  "eventId": "evt-123",
  "price": "50000",
  "quantity": "0.01",
  "side": "BUY",
  "tradedAt": "2024-01-15T10:00:00Z"
}
```

---

## Priority 6 — 조회 API Pagination / Limit [IMPROVE]

### 6-1. 주문 목록 조회 — limit 없음

```java
// OrderService.java:220
orderRepository.findAllByUserIdOrderByCreatedAtDesc(currentUserId);
```

전체 반환이므로 주문이 많아지면 OOM 또는 느린 응답이 발생한다.

**해결**:

```
GET /api/v1/orders?status=OPEN&limit=50&offset=0
```

기본값 `limit=50`, 최대값 `limit=200` 을 Bean Validation으로 강제한다.

### 6-2. 체결 내역 조회 — cursor 없음

```
GET /api/v1/orders/{orderId}/fills
```

실시간 거래가 활발한 환경에서 페이지 이동 중 데이터가 밀린다.  
`lastFillId` 를 커서로 사용하는 keyset pagination으로 전환한다.

```
GET /api/v1/orders/{orderId}/fills?lastFillId=0&limit=50
```

### 6-3. Swagger 문서와 구현 불일치

`limit`, `offset`, `lastFillId` 파라미터가 OpenAPI 명세에 없다.  
구현과 동시에 `@Parameter` 애노테이션으로 문서를 업데이트한다.

---

## Priority 7 — 테스트 격리 [IMPROVE]

### 현상

통합 테스트에서 `@BeforeEach` 로 `matchingEngine.clearAll()` 을 호출하면  
**in-memory 오더북만 초기화**되고 DB 체결 데이터는 남는다.

```java
// MatchingEngine.java:63
public void clearAll() {
    orderBooks.clear();  // DB를 비우는 게 아니다
}
```

결과적으로 DB에 이전 테스트 체결 내역이 누적된 채로 오더북만 빈 상태가 되어  
"재시작 후 복구" 시나리오와 다른 비현실적인 상태에서 테스트가 진행된다.

### 해결

**매칭/정산 E2E 테스트**: DB cleanup 순서를 외래 키 의존 순으로 명시한다.

```java
@BeforeEach
void setUp() {
    tradeRepository.deleteAll();
    walletLedgerRepository.deleteAll();
    orderRepository.deleteAll();
    walletRepository.deleteAll();
    userRepository.deleteAll();
    matchingEngine.clearAll();  // DB 정리 후 오더북 초기화
}
```

**단위 테스트**: `@Transactional` 으로 감싸 자동 롤백한다.

---

## Priority 8 — docker-compose MySQL 패스워드 불일치 [IMPROVE]

### 현상

`docker-compose.yml` 과 `application.properties` 의 MySQL 패스워드가 다르면  
`docker compose up` 후 Spring Boot 기동 시 DB 연결 실패.

### 해결

두 파일 모두 동일한 환경변수 참조로 통일한다.

```yaml
# docker-compose.yml
environment:
  MYSQL_PASSWORD: coinflow
```

```properties
# application.properties
spring.datasource.password=${DB_PASSWORD:coinflow}
```

또는 docker-compose.yml 에서 `.env` 파일로 값을 관리한다.

---

## 요약 표

| # | 항목 | 심각도 | 브랜치 |
|---|------|--------|--------|
| 1 | 오더북 rollback 불일치 (planMatch/applyMatchPlan) | BLOCKER | feat/phase1-fix |
| 2 | getBuySide/getSellSide 동시 읽기 (marketLock 재사용) | BLOCKER | feat/phase1-fix |
| 3 | Maker order row lock 누락 (findByIdWithLock) | BLOCKER | feat/phase1-fix |
| 4 | DepositRequest 검증 없음 (@NotBlank) | BLOCKER | feat/phase1-fix |
| 5 | Outbox timeout / schema / 순서 / dedup / broadcast key | IMPROVE | feat/20/outbox-publisher |
| 6 | 조회 API pagination / Swagger 불일치 | IMPROVE | feat/phase1-fix |
| 7 | 테스트 격리 (clearAll = in-memory만 초기화) | IMPROVE | feat/phase1-fix |
| 8 | docker-compose MySQL 패스워드 불일치 | IMPROVE | feat/19/kafka-setup |

BLOCKER 항목은 Phase 2 시작 전에 수정을 완료하고, IMPROVE 항목은 해당 이슈 브랜치에서 병행 처리한다.
