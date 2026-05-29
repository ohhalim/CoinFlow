# OOP Refactoring Review

## 목적

- 주문 생성/접수/취소 리팩토링 이후 남은 책임 혼재 구간 정리
- OOP 관점의 모듈 경계, 응집도, 중복 흐름 검토
- 후속 리팩토링 우선순위와 PR 분리 기준 정의

## 현재 완료 범위

| 범위 | 상태 |
|---|---|
| 동기 주문 생성 흐름 분리 | 완료 |
| 비동기 주문 접수 흐름 추가 | 완료 |
| 주문 취소 흐름 분리 | 완료 |
| 주문 조회 흐름 분리 | 미진행 |
| 지갑 모듈 경계 정리 | 미진행 |
| 정산 책임 세분화 | 미진행 |
| 도메인 이벤트 payload/write 책임 분리 | 미진행 |

## 검토 기준

- 서비스 클래스의 단일 책임 여부
- 도메인 모듈 간 repository 직접 접근 여부
- command/query 책임 혼재 여부
- lock 획득, transaction callback, afterCommit 흐름의 중복 여부
- 상태 전이와 자산 변경의 도메인 불변식 보호 여부
- PR 단위로 분리 가능한 리스크 수준

## 핵심 판단

- 다음 작업의 1차 목표는 기능 변경이 아닌 책임 분리
- 주문 생성/접수/취소는 분리 완료, 조회 책임만 `OrderService`에 잔존
- 가장 큰 구조적 결합은 order 패키지의 wallet repository 직접 접근
- settlement 분리 이전에 wallet 모듈 경계 정립 필요
- `OrderBookBroadcaster`는 현재 응집도가 높아 분리 보류
- `MarketLockTemplate`은 lock release 타이밍 영향이 커서 즉시 도입 보류

## 우선순위

| 순위 | 리팩토링 항목 | 리스크 | 판단 |
|---:|---|---|---|
| 1 | 주문 조회 흐름 책임 분리 | 낮음 | `OrderService` facade 정리 완료 단계 |
| 2 | MatchingEngine book 조회/생성 중복 제거 | 낮음 | 반복 `computeIfAbsent` 제거 |
| 3 | 주문 취소 market lock 패턴 통일 | 낮음 | cancel 경로도 `MarketOrderLockScope` 기준 적용 |
| 4 | client order id 중복 예외 변환 통합 | 낮음 | 동기/비동기 주문 생성 catch 중복 제거 |
| 5 | Order 팩토리 파라미터 축소 | 낮음 | 12개 파라미터 호출부 가독성 개선 |
| 6 | 지갑 주문 작업 경계 분리 | 중간 | order 패키지의 wallet repository 직접 접근 제거 |
| 7 | 회원 가입 시 지갑 생성 책임 이동 | 낮음 | auth 패키지의 wallet repository 직접 접근 제거 |
| 8 | ACCEPTED 주문 재등록 책임 분리 | 낮음 | 접수와 복구 책임 분리 |
| 9 | 주문 실행 후 오더북 반영 책임 분리 | 중간 | 동기/비동기 processor afterCommit 중복 제거 |
| 10 | 도메인 이벤트 payload 생성 책임 분리 | 중간 | payload 생성과 outbox write 분리 |
| 11 | 주문 정산 책임 세분화 | 높음 | wallet 경계 분리 이후 단계적 진행 |
| 12 | TransactionTemplate 빈 주입 전환 | 낮음 | 생성자 내부 `new TransactionTemplate` 반복 제거 |
| 보류 | OrderBookBroadcaster 분리 | 중간 | 현재 단일 흐름과 coalescing 상태 응집도 유지 |

## 상세 리뷰

### 1. 주문 조회 흐름 책임 분리

대상:

- `OrderService.getOrder()`
- `OrderService.getOrders()`

현재 구조:

- `OrderService`가 생성/접수/취소 위임과 조회 로직을 함께 보유
- 생성, 접수, 취소 책임은 별도 서비스로 분리 완료

개선 방향:

- `OrderQueryService` 추가
- 주문 단건 조회와 주문 목록 조회 이동
- `OrderService`는 facade로 위임만 유지

완료 기준:

- 기존 REST 응답 계약 유지
- `OrderService`에서 read-only transaction 제거
- `./gradlew test` 통과

### 2. MatchingEngine book 조회/생성 중복 제거

대상:

- `MatchingEngine`의 반복 `orderBooks.computeIfAbsent(...)`

현재 구조:

- market symbol 기준 `MemoryOrderBook` 조회/생성 코드 반복

개선 방향:

- `private MemoryOrderBook getOrCreateBook(Market market)` 추출
- symbol 기반 조회만 필요한 메서드는 기존 방식 유지 또는 별도 `getBook(String marketSymbol)` 검토

완료 기준:

- 기능 변경 없음
- matching 관련 테스트 통과

### 3. 주문 취소 market lock 패턴 통일

대상:

- `OrderCancelService`
- `MarketOrderLockService`

현재 구조:

- 주문 생성/비동기 처리: `MarketOrderLockScope` 사용
- 주문 취소: `ReentrantLock` 직접 획득/해제

개선 방향:

- cancel 경로도 lock scope 기반으로 통일
- `MarketOrderLockService.acquire(Long marketId)` 또는 cancel 전용 메서드 추가 검토
- cancel lock hold 지표 필요 여부 검토

완료 기준:

- cancel 정합성 테스트 통과
- lock release 중복/누락 없음

### 4. client order id 중복 예외 변환 통합

대상:

- `SyncOrderProcessor`
- `AcceptedOrderService`
- `ClientOrderIdService`

현재 구조:

- `DataIntegrityViolationException` catch 블록 중복
- duplicate client order id 판단 로직은 `ClientOrderIdService`에 위치

개선 방향:

- `ClientOrderIdService`에 예외 변환 메서드 추가
- 동기/비동기 주문 생성 경로의 catch 처리 통합

완료 기준:

- 중복 client order id 테스트 통과
- 기존 `DUPLICATE_CLIENT_ORDER_ID` 응답 유지

### 5. Order 팩토리 파라미터 축소

대상:

- `Order.create(...)`
- `Order.accepted(...)`
- 호출부: `SyncOrderProcessor`, `AcceptedOrderService`

현재 구조:

- factory method가 12개 파라미터 수신
- `Market`, `CreateOrderCommand`, `sequence`, `clientOrderId`를 호출부에서 해체해 전달

개선 방향:

- `Order.create(Long userId, Market market, CreateOrderCommand command, Long sequence, String clientOrderId)` 형태 검토
- order domain이 service command에 의존하는 것이 부담되면 `OrderCreateParams` value object 도입

완료 기준:

- factory 호출부 가독성 개선
- order 생성 테스트 통과

### 6. 지갑 주문 작업 경계 분리

대상:

- `OrderAssetLockService`
- `OrderSettlementService`
- `OrderCancelService`
- `AcceptedOrderProcessor`
- wallet repository 직접 접근 구간

현재 구조:

- order 패키지가 `WalletRepository`, `WalletLedgerRepository`, `WalletLedgerJdbcRepository` 직접 사용
- wallet lock, unlock, consume, ledger 생성 책임이 order 패키지에 분산

문제 지점:

- wallet 도메인 내부 규칙이 order 패키지에 노출
- cancel/reject의 locked asset release 흐름 중복
- settlement 분리 이후에도 wallet repository 결합 유지 가능성

개선 방향:

- wallet 패키지에 주문용 지갑 작업 서비스 추가
- 후보명:
  - `WalletOrderOperationService`
  - `WalletTradeSettlementService`
  - `MultiWalletLockService`
- 제공 책임:
  - 주문 자산 잠금
  - 주문 취소/거절 locked asset 해제
  - 정산용 다중 지갑 lock ordering
  - 지갑 mutation과 ledger 생성

완료 기준:

- order 패키지의 wallet repository 직접 의존 감소
- cancel/reject release 중복 제거
- 정산 테스트와 동시성 테스트 통과

### 7. 회원 가입 시 지갑 생성 책임 이동

대상:

- `AuthService.signup()`

현재 구조:

- auth 패키지가 `AssetRepository`, `WalletRepository`를 사용해 지갑 생성

개선 방향:

- wallet 패키지에 `createWalletsForUser(Long userId)` 추가
- 또는 회원 생성 이벤트 기반 지갑 생성 검토

완료 기준:

- 회원 가입 응답 계약 유지
- 지갑 생성 테스트 통과

### 8. ACCEPTED 주문 재등록 책임 분리

대상:

- `AcceptedOrderService.requeueAcceptedOrder()`
- `AcceptedOrderRecoveryScheduler`

현재 구조:

- 신규 접수와 재등록 판단이 `AcceptedOrderService`에 혼재

개선 방향:

- 과분리 지양
- `AcceptedOrderRequeueService` 또는 recovery scheduler 쪽으로 재등록 판단 이동
- queue 등록 공통 메서드는 유지 가능

완료 기준:

- 오래된 ACCEPTED 주문 재등록 테스트 통과
- 중복 queue 등록 방지 유지

### 9. 주문 실행 후 오더북 반영 책임 분리

대상:

- `SyncOrderProcessor`
- `AcceptedOrderProcessor`

현재 구조:

- 두 processor가 afterCommit에서 동일한 흐름 수행
  - `matchingEngine.applyMatchPlan`
  - auto canceled maker 오더북 제거
  - 실패 시 `OrderBookRecoveryService.rebuildAfterApplyFailure`
  - market lock release

개선 방향:

- `OrderBookCommitSynchronizer` 추가
- afterCommit 오더북 반영과 복구 로직 통합
- lock release 정책은 기존 동작 유지 후 분리 범위 최소화

완료 기준:

- sync/async 주문 생성 테스트 통과
- 오더북 복구 테스트 통과
- lock release 누락 없음

### 10. 도메인 이벤트 payload 생성 책임 분리

대상:

- `DomainEventRecorder`

현재 구조:

- 이벤트 payload 생성, envelope 생성, JSON 직렬화, JDBC batch insert를 한 클래스가 담당

개선 방향:

- `DomainEventPayloadFactory` 추가
- `DomainEventRecorder`는 outbox write 중심으로 축소
- 장기적으로 `DomainEventPublisher` 인터페이스 도입 검토

완료 기준:

- outbox 저장 payload 구조 유지
- 이벤트 관련 통합 테스트 통과

### 11. 주문 정산 책임 세분화

대상:

- `OrderSettlementService`

현재 구조:

- maker order lock
- 주문 fill
- wallet lock ordering
- wallet mutation
- trade 저장
- settlement event 저장
- wallet ledger 생성/저장
- dust maker cancel

개선 방향:

1. `DustOrderCancelPolicy` 분리
2. wallet lock/mutation은 wallet 경계 분리 이후 이동
3. ledger 생성 factory 분리 검토
4. event 저장 책임은 `DomainEventPayloadFactory` 이후 재검토

완료 기준:

- 정산 경계 케이스 테스트 통과
- zero-quote/dust/cancel 경합 테스트 유지
- 성능 측정 지표 악화 없음

### 12. TransactionTemplate 빈 주입 전환

대상:

- `SyncOrderProcessor`
- `AcceptedOrderProcessor`
- `AcceptedOrderService`
- `OrderCancelService`
- `OrderBookRecoveryService`

현재 구조:

- 생성자에서 `new TransactionTemplate(transactionManager)` 반복

개선 방향:

- `TransactionTemplate` 빈 등록
- 각 서비스는 `TransactionTemplate` 직접 주입

완료 기준:

- transaction 동작 변경 없음
- 전체 테스트 통과

## 보류 항목

### OrderBookBroadcaster 분리

보류 근거:

- Kafka listener, event filter, coalescing, snapshot, STOMP publish가 하나의 broadcast 흐름으로 응집
- `pendingEvents`, `scheduledMarkets` 상태 공유가 핵심
- 현재 분리 시 클래스 수 증가 대비 책임 분리 효과 제한적

재검토 조건:

- orderbook broadcast 정책 추가
- market별 throttling/priority 정책 추가
- snapshot publisher 재사용 필요

### MarketLockTemplate 도입

보류 근거:

- market lock release 타이밍은 정합성과 성능 지표에 직접 영향
- afterCommit/afterCompletion 중복 release 방어 로직이 민감
- 현재 추상화 시 디버깅 복잡도 증가 가능

재검토 조건:

- `OrderBookCommitSynchronizer` 분리 이후에도 processor lock lifecycle 중복 유지
- cancel/sync/async lock 획득 정책 통합 필요

## 실행 계획

### Phase 1: 낮은 리스크 정리

1. `OrderQueryService` 분리
2. `MatchingEngine.getOrCreateBook()` 추출
3. `OrderCancelService` lock scope 통일
4. client order id 예외 변환 통합
5. `Order` factory 파라미터 축소

### Phase 2: 모듈 경계 정리

1. wallet 주문 작업 서비스 추가
2. cancel/reject release 중복 제거
3. auth 가입 후 지갑 생성 책임 이동
4. order 패키지의 wallet repository 직접 접근 축소

### Phase 3: 실행 흐름 정리

1. `OrderBookCommitSynchronizer` 추가
2. sync/async processor afterCommit 중복 제거
3. ACCEPTED 재등록 책임 분리

### Phase 4: 정산/이벤트 세분화

1. `DomainEventPayloadFactory` 분리
2. `DustOrderCancelPolicy` 분리
3. settlement wallet mutation 분리
4. ledger 생성 책임 분리

## PR 분리 기준

- 낮은 리스크 변경은 1~2개 항목까지 묶음 가능
- wallet 경계 변경은 단독 PR
- settlement 변경은 단계별 단독 PR
- lock lifecycle 변경은 단독 PR
- 각 PR은 `./gradlew test` 통과 후 병합
- 성능 경로 변경 시 기존 k6 기준 재측정 여부 별도 판단

