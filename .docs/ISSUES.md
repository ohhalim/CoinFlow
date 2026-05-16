# CoinFlow 현재 이슈 목록

이 문서는 Phase 1 완료 이후 코드 리뷰에서 발견된 이슈를 심각도 순으로 정리한다.
Phase 1 BLOCKER 이슈(planMatch/applyMatchPlan, marketLock 재사용, findByIdWithLock, DepositRequest 검증, Pagination)는 [v2/ISSUES.md](./v2/ISSUES.md)에서 모두 수정 완료되었다.

---

## Priority 1 — Zero-quote 체결 생성 방지 [BLOCKER]

### 현상

`planMatch()`는 `matchedQuoteAmount`가 0이어도 `MatchResult`를 생성한다.

```java
// MemoryOrderBook.java
BigDecimal matchedQuoteAmount = maker.price()
        .multiply(matchedQuantity)
        .setScale(amountScale, RoundingMode.DOWN);
// matchedQuoteAmount == 0 체크 없음
results.add(new MatchResult(..., matchedQuoteAmount));
```

DB에는 `chk_trades_amounts CHECK (quote_amount > 0)` 제약이 있으므로, 커밋 시 `DataIntegrityViolationException`이 발생하고 트랜잭션 전체가 500으로 롤백된다.

### 원인

PRD 9절에 명시된 정책이 구현되지 않았다.

```
rounding 후 trade.quote_amount가 0이 되는 체결은 만들지 않는다.
매칭 중 특정 후보와의 체결 결과가 zero-quote가 되면 해당 시점에서 매칭을 중단한다.
```

### 수정

```java
// planMatch() 루프 내부
BigDecimal matchedQuoteAmount = maker.price()
        .multiply(matchedQuantity)
        .setScale(amountScale, RoundingMode.DOWN);

if (matchedQuoteAmount.signum() == 0) break; // 추가
```

매칭 중단 시 이미 체결된 부분이 있으면 taker를 `PARTIALLY_FILLED`로, 없으면 `OPEN`으로 오더북에 등록한다. zero-quote를 유발한 maker는 오더북에서 제거하지 않는다.

### 검증

- `MemoryOrderBookTest`에 zero-quote 시 `MatchResult`를 생성하지 않고 기존 maker를 유지하는 단위 테스트를 추가했다.

---

## Priority 2 — Dust maker 자동 취소 미구현 [BLOCKER]

### 현상

체결 후 maker의 잔여 수량이 dust 상태가 되어도 취소 처리가 없다.

### 원인

PRD 9절 정책이 구현되지 않았다.

```
체결 후 maker의 남은 수량이 dust가 되어
DOWN(maker_price × maker_remaining, amount_scale) == 0이면
해당 maker를 CANCELED 처리하고 잔여 locked를 해제한다.
원장에 ORDER_CANCEL_RELEASE를 기록한다.
```

### 수정

`OrderService.settle()` 내부, 각 체결의 wallet ledger 기록 직후:

```java
// 각 trade 처리 후 dust 체크
if (maker.getRemainingQuantity().signum() > 0) {
    BigDecimal dustCheck = maker.getPrice()
            .multiply(maker.getRemainingQuantity())
            .setScale(amountScale, RoundingMode.DOWN);
    if (dustCheck.signum() == 0) {
        Wallet makerLockedWallet = takerIsBuy ? sellerBaseWallet : buyerQuoteWallet;
        BigDecimal dustRelease = maker.releasableAmount();
        makerLockedWallet.unlock(dustRelease);
        maker.cancel();
        walletLedgerRepository.save(WalletLedger.create(
                makerLockedWallet, LedgerType.ORDER_CANCEL_RELEASE,
                dustRelease, dustRelease.negate(),
                maker.getId(), tradeId
        ));
        eventRecorder.recordOrderCanceled(maker, maker.getLockedAsset(), dustRelease.toPlainString());
    }
}
```

### 검증

- `MatchingSettlementTest`에 dust maker 자동 취소, locked balance 해제, 오더북 제거를 검증하는 통합 테스트를 추가했다.

---

## Priority 3 — Deposit API 프로필 미분리 [BLOCKER]

### 현상

`POST /api/v1/wallets/deposit`이 운영 프로필에서도 열려 있다. 인증된 사용자라면 누구나 자신의 지갑 잔액을 직접 증가시킬 수 있다.

### 원인

PRD는 입금을 명시적으로 MVP 제외 범위로 정의한다 (PRD.md line 97).

```
입금, 출금 — 제외 범위
```

현재 구현은 seed balance 목적으로 열었지만, 운영 프로필 분리 없이 노출되어 있다.

### 수정

**옵션 A — 완전 제거 (권장)**: 테스트는 Repository/Helper를 통한 seed로 처리한다.

```java
// MatchingSettlementTest.setUp() 패턴으로 대체
wallet.deposit(amount);
walletRepository.save(wallet);
```

**옵션 B — 프로필 가드**:

```java
@Profile({"local", "dev", "test"})
@RestController
...
public class WalletController {
    @PostMapping("/deposit")
    public WalletResponse deposit(...) { ... }
}
```

**옵션 C — dev 전용 컨트롤러 분리**: `DevWalletController`를 별도 파일로 분리하고 `@Profile("dev")` 적용.

어느 방식이든 API 문서(API.md)에 "dev/test only"를 명시한다.

---

## Priority 4 — API 응답/파라미터 명세 불일치 [IMPROVE]

### 4-1. MarketResponse 필드 불일치

API.md 명세와 구현이 다르다.

| 필드 | API.md | 구현 |
|------|--------|------|
| 시장 심볼 | `"market"` | `"symbol"` |
| `amountScale` | 있음 | **없음** |
| `cancelOnly` | 있음 | **없음** |

```java
// 수정 전
public record MarketResponse(String symbol, ...)

// 수정 후
public record MarketResponse(
        String market,        // symbol → market
        String amountScale,   // 추가
        boolean cancelOnly,   // 추가
        ...
)
```

### 4-2. FillResponse 필드 불일치

| 필드 | API.md | 구현 |
|------|--------|------|
| `side` | 있음 | **없음** |
| `settled` | 있음 | **없음** |
| `liquidity` 값 | `"M"` / `"T"` | `"MAKER"` / `"TAKER"` |

```java
// 수정 후
public record FillResponse(
        ...
        String side,          // "BUY" / "SELL" 추가
        String liquidity,     // "MAKER" → "M", "TAKER" → "T"
        boolean settled,      // 항상 true (동일 트랜잭션 정산)
        ...
)
```

### 4-3. GET /fills — orderId 필터 없음

API.md는 `orderId` 쿼리 파라미터를 지원한다고 명시한다.

```
GET /api/v1/fills?market=BTC-KRW&orderId=1001&limit=50
```

`TradeController.getFills()`에 `orderId` 파라미터와 Repository 쿼리를 추가한다.

### 4-4. GET /wallets/ledgers — limit 파라미터 없음

API.md는 `limit` 파라미터를 명시한다.

```
GET /api/v1/wallets/ledgers?asset=KRW&limit=50
```

`WalletService.getLedgers()`에 `limit`/`offset` 또는 커서 기반 페이지네이션을 추가한다.

---

## Priority 5 — OrderBook 가격 합산 및 depth 파라미터 미구현 [IMPROVE]

### 현상

`OrderBookResponse.of()`가 같은 가격의 `OrderBookEntry`를 합산하지 않고 1:1로 노출한다. `depth` 파라미터도 없다.

### 원인

API.md line 668 규칙이 구현되지 않았다.

```
같은 가격 주문은 합산 수량으로 응답한다.
depth: 가격 레벨 개수, 기본값 10
```

### 수정

```java
// OrderBookResponse.of()
private static List<PriceLevel> aggregate(List<OrderBookEntry> entries, int depth) {
    return entries.stream()
            .collect(Collectors.groupingBy(
                    e -> e.price().toPlainString(),
                    Collectors.reducing(BigDecimal.ZERO,
                            OrderBookEntry::remainingQuantity, BigDecimal::add)
            ))
            .entrySet().stream()
            .sorted(...)  // 정렬 유지
            .limit(depth)
            .map(e -> new PriceLevel(e.getKey(), e.getValue().toPlainString()))
            .toList();
}
```

```java
// MarketController
@GetMapping("/{market}/orderbook")
public OrderBookResponse getOrderBook(
        @PathVariable String market,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) int depth
) { ... }
```

---

## Priority 6 — cancelOrder() order row lock 없음 [IMPROVE]

### 현상

취소 트랜잭션 내부에서 `findByIdAndUserId()`를 사용한다 (`SELECT FOR UPDATE` 없음).

```java
// OrderService.java
Order lockedOrder = orderRepository.findByIdAndUserId(orderId, currentUserId)
        .orElseThrow(...); // FOR UPDATE 없음
```

### 원인

Plan.md가 명시한 계약이다.

```
4. order row lock (SELECT FOR UPDATE)
```

현재는 market lock이 직렬화를 보장하므로 실제 race는 없다. 하지만 설계 문서 계약과 다르고, market lock을 우회하는 경로가 추가될 경우 즉시 위험해진다.

### 수정

```java
// OrderService.cancelOrder() 내부
Order lockedOrder = orderRepository.findByIdWithLock(orderId)
        .filter(o -> o.getUserId().equals(currentUserId))
        .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
```

또는 `findByIdAndUserIdWithLock()` 쿼리를 Repository에 추가한다.

---

## Priority 7 — wallet lock 순서 문서 계약 불일치 [IMPROVE]

### 현상

`settle()`에서 wallet을 잠그는 순서가 문서 계약과 다르다.

```java
// 현재 순서 (업무 순서 기준)
findByUserIdAndAssetWithLock(buyUserId,  baseAsset)   // buyerBase
findByUserIdAndAssetWithLock(sellUserId, quoteAsset)  // sellerQuote
findByUserIdAndAssetWithLock(sellUserId, baseAsset)   // sellerBase
findByUserIdAndAssetWithLock(buyUserId,  quoteAsset)  // buyerQuote
```

### 원인

ERD.md 계약이다.

```
여러 wallet을 동시에 잠글 때는 (user_id, asset) 오름차순으로 잠근다.
```

현재는 market lock 덕분에 데드락이 발생하지 않는다. 향후 cross-market 작업이 추가되면 위험해진다.

### 수정

wallet ID 목록을 `(userId, asset)` 기준으로 정렬 후 일괄 잠금하는 헬퍼를 추가한다.

```java
// WalletLockHelper 또는 WalletRepository 유틸
List<Wallet> lockWalletsInOrder(List<WalletKey> keys) {
    return keys.stream()
            .sorted(Comparator.comparingLong(WalletKey::userId)
                    .thenComparing(WalletKey::asset))
            .map(k -> walletRepository.findByUserIdAndAssetWithLock(k.userId(), k.asset()))
            .toList();
}
```

---

## Priority 8 — afterCommit 복구 설계 보강 [IMPROVE]

### 현상

`afterCommit()`에서 오더북 반영 실패 시 로그만 남긴다.

```java
// OrderService.java
} catch (Exception e) {
    log.error("오더북 applyMatchPlan 실패: ...", e);
}
```

### 원인

Plan.md 계약이다.

```
commit 이후 오더북 반영 중 예외가 발생하면 해당 market의 오더북을 DB에서 재빌드한다.
재빌드도 실패하면 해당 market을 cancel_only = true로 전환하고 수동 복구를 기다린다.
```

### 수정 방향

단기 MVP 수준:

1. `log.error` + Actuator metric increment (`meterRegistry.counter("orderbook.apply.failure")`)
2. 수동 트리거용 관리 엔드포인트 추가:

```
POST /actuator/orderbook/rebuild?marketId=1
```

중기 (Phase 2):

`ApplicationEventPublisher` + `@Transactional(propagation = REQUIRES_NEW)`으로 분리하여 재빌드 → 실패 시 `cancel_only = true` 업데이트를 별도 트랜잭션으로 처리한다.

---

## Priority 9 — docker-compose Kafka KRaft 불일치 [IMPROVE]

### 현상

v2/PRD.md는 KRaft(Zookeeper 없음)를 선택 이유로 명시했으나, `docker-compose.yml`은 Zookeeper 기반 Kafka를 사용한다.

또한 `build.gradle`에 Kafka/WebSocket 의존성이 없어 앱 자체는 Kafka 없이 기동한다.

### 수정

v2 구현 시작 시:

1. `docker-compose.yml`에서 Zookeeper 제거, KRaft 모드 Kafka로 교체
2. `build.gradle`에 `spring-kafka`, `spring-boot-starter-websocket` 추가
3. v2/PRD.md의 docker-compose 설명과 실제 파일 일치 확인

---

## 요약 표

| # | 항목 | 심각도 | 상태 |
|---|------|--------|------|
| 1 | Zero-quote break (planMatch) | BLOCKER | 수정 완료 |
| 2 | Dust maker 자동 취소 | BLOCKER | 수정 완료 |
| 3 | Deposit API 프로필 가드/제거 | BLOCKER | 수정 완료 |
| 4 | API 응답/파라미터 명세 정합 | IMPROVE | 수정 완료 |
| 5 | OrderBook 가격 합산 + depth | IMPROVE | 수정 완료 |
| 6 | cancelOrder row lock | IMPROVE | 수정 완료 |
| 7 | wallet lock 정렬 | IMPROVE | 수정 완료 |
| 8 | afterCommit 복구 설계 보강 | IMPROVE | 수정 완료 |
| 9 | docker-compose KRaft 전환 | IMPROVE | 수정 완료 |

위 항목은 현재 리팩토링에서 모두 처리 완료했다.
