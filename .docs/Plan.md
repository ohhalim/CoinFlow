# CoinFlow MVP 구현 계획

이 문서는 CoinFlow MVP의 구현 순서와 책임 경계를 정리한다.

최신 기준 문서는 아래 순서를 따른다.

1. [PRD.md](./PRD.md)
2. [ERD.md](./ERD.md)
3. [API.md](./API.md)
4. [TestPlan.md](./TestPlan.md)
5. [Reference.md](./Reference.md)
6. [ERDCloud.sql](./ERDCloud.sql)

`Plan.md`는 위 문서를 구현 순서로 풀어낸 실행 계획이다.

---

## 1. 한 줄 정의

> 단일 인스턴스 환경에서 회원가입/로그인, 지정가 주문 생성/취소, 가격-시간 우선 매칭, 체결, 정산, append-only 원장, 조회까지 검증하는 거래소 코어 백엔드 MVP

---

## 2. MVP 목표

| 증명 항목 | 구현 방법 |
|---|---|
| 사용자별 데이터 분리 | JWT access token에서 현재 사용자 ID 추출 |
| 자산 잠금 모델 | `wallets.available_balance / locked_balance` 분리 |
| 가격-시간 우선 매칭 | 가격 우선 + `orders.sequence` 시간 우선 |
| 주문 상태 전이 | `OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELED` |
| 체결 기록 | `trades` 저장 |
| 자산 이동 추적 | `wallet_ledgers` append-only 기록 |
| 이벤트 추적 | `domain_events` 내부 이벤트 로그 |
| 조회 모델 | 메모리 오더북 + DB 조회 API |
| 정합성 검증 | `TestPlan.md` 기반 통합 테스트 |

---

## 3. MVP 범위

### 포함

- 회원가입
- 로그인
- JWT access token 발급/검증
- 현재 사용자 조회
- 자산/시장 seed data
- 지갑 seed balance
- 지갑 조회
- 원장 조회
- 지정가 매수/매도 주문 생성
- 주문 취소
- 가격-시간 우선 매칭
- 부분 체결 / 완전 체결
- 체결 정산
- 체결 조회
- 사용자 fill 조회
- 메모리 오더북 조회
- 서버 시작 시 미체결 주문 기반 오더북 초기화
- 내부 이벤트 로그 기록

### 제외

- 입금/출금 API
- 시장가 주문
- IOC/FOK/GTT
- post-only 주문
- iceberg 주문
- 수수료
- dust 처리
- refresh token
- 이메일 인증
- 비밀번호 재설정
- OAuth/social login
- role/permission 기반 권한 관리
- Kafka / Redis / MQ
- WebSocket
- 서버 분리
- replay / redrive / recovery
- 관리자 페이지

---

## 4. 핵심 설계 원칙

### 4.1 DB가 Source of Truth

MVP에서 DB가 정합성의 기준이다.

| 테이블 | 역할 |
|---|---|
| `users` | 회원가입/로그인 사용자 |
| `assets` | 자산 표시명과 상태 정책 |
| `markets` | 시장과 주문 검증 정책 |
| `wallets` | 사용자별 자산 현재 스냅샷 |
| `wallet_ledgers` | 모든 지갑 변동 이력 |
| `order_sequences` | 시장별 주문 sequence |
| `orders` | 주문 상태와 수량 |
| `trades` | 체결 기록 |
| `domain_events` | 내부 이벤트 로그 |

### 4.2 메모리 오더북은 파생 조회 모델

메모리 오더북은 매칭 후보 조회와 호가 조회를 위한 파생 상태다.

- DB가 source of truth다.
- 트랜잭션 commit 이후에만 메모리 오더북을 변경한다.
- 서버 시작 시 `OPEN`, `PARTIALLY_FILLED` 주문을 DB에서 읽어 초기화한다.

### 4.3 동일 시장 명령은 순차 처리

같은 market의 주문 생성/취소/매칭은 순차 처리한다.

MVP 구현 선택지:

- 1차 단순 구현: 시장별 `ReentrantLock`으로 직렬화. 전역 lock이 아니라 market id 기준으로 lock 인스턴스를 관리한다 (`ConcurrentHashMap<Long, ReentrantLock>`). 같은 시장의 주문만 직렬화되고 다른 시장 간 블로킹은 없다.
- 이후 개선: market 단위 queue/worker로 전환

처음 구현에서는 성능보다 정합성을 우선한다.

시장 lock의 범위는 트랜잭션 시작부터 commit 이후 메모리 오더북 반영 완료까지다. 트랜잭션 commit 직후 오더북 갱신 전에 다음 주문이 진입하면 stale 오더북으로 매칭이 발생할 수 있다.

구현은 `TransactionTemplate`을 사용해 DB 트랜잭션 완료 후 메모리 오더북을 반영한다. 트랜잭션 내부에서는 오더북을 직접 변경하지 않고, commit 이후 적용할 변경 사항만 결과 객체로 반환한다.

commit 이후 오더북 반영 중 예외가 발생하면 해당 market의 오더북을 DB에서 재빌드한다. 재빌드도 실패하면 해당 market을 `cancel_only = true`로 전환하고 수동 복구를 기다린다. 어느 경우에도 이미 commit된 DB 데이터는 변경하지 않는다.

### 4.4 원장은 append-only

`wallet_ledgers`는 수정/삭제하지 않는다.

필수 필드:

| 필드 | 의미 |
|---|---|
| `delta_available` | available 변화량 |
| `delta_locked` | locked 변화량 |
| `available_balance_after` | 변경 후 available |
| `locked_balance_after` | 변경 후 locked |
| `order_id` | 관련 주문 ID |
| `trade_id` | 관련 체결 ID |

`reference_type`, `reference_id`는 DB에 저장하지 않는다. API 응답에서 참조 타입이 필요하면 `order_id`, `trade_id` 기준으로 파생한다.

---

## 5. 도메인 모델 요약

### User

```text
id, email, password_hash, nickname, status
```

### Asset

```text
code, name, display_name, status
```

### Market

```text
symbol, display_name, base_asset, quote_asset,
amount_scale,
tick_size, step_size, min_order_quantity, min_order_amount,
status, cancel_only
```

### Wallet

```text
user_id, asset, available_balance, locked_balance
unique(user_id, asset)
```

### Order

```text
user_id, market_id, market_symbol,
side, type, time_in_force,
price, original_quantity, remaining_quantity, executed_quantity,
executed_quote_amount, locked_asset, locked_amount,
status, sequence
```

### Trade

```text
market_id, market_symbol,
buy_order_id, sell_order_id,
maker_order_id, taker_order_id,
buy_user_id, sell_user_id,
price, quantity, quote_amount
```

### WalletLedger

```text
user_id, wallet_id, asset, type,
delta_available, delta_locked,
available_balance_after, locked_balance_after,
order_id, trade_id
```

---

## 6. 불변조건

### Wallet

```text
available_balance >= 0
locked_balance >= 0
```

### Order

```text
original_quantity = executed_quantity + remaining_quantity
remaining_quantity >= 0
executed_quantity >= 0
locked_amount = 0  (status = FILLED 또는 CANCELED)
```

취소된 주문도 위 수량 불변식은 유지한다. `CANCELED` 주문의 `remaining_quantity`는 취소된 잔여 수량이다. `FILLED` 또는 `CANCELED` 상태가 되면 `locked_amount`는 반드시 0이어야 한다.

### Trade

```text
quote_amount = price * quantity 를 markets.amount_scale 기준으로 DOWN rounding
```

BUY 주문의 `locked_amount`는 `price * remaining_quantity`를 `markets.amount_scale` 기준으로 CEILING rounding하여 계산한다. 부분 체결 후에는 기존 잠금액을 단순 차감하지 않고 남은 수량 기준으로 재계산한다.

### OrderBook

```text
오더북 포함: OPEN, PARTIALLY_FILLED
오더북 제외: FILLED, CANCELED
```

---

## 7. 처리 흐름

### 7.1 주문 생성

```text
1.  JWT에서 currentUserId 추출
2.  market 조회
3.  market status 검증
4.  side/type/timeInForce 검증
5.  price tickSize 검증
6.  quantity stepSize 검증
7.  minOrderQuantity / minOrderAmount 검증
8.  clientOrderId 중복 검증
9.  order sequence 발급
10. 메모리 오더북 후보 기준 매칭 후보 목록 생성
11. 자기 체결 사전 검증 (교차 가능한 후보 중 userId == currentUserId인 주문이 하나라도 있으면 SELF_TRADE_NOT_ALLOWED 반환, 이후 단계 진행 없음)
12. maker order row lock 및 상태/수량 재검증
13. 체결에 관련된 모든 wallet 확정 (taker wallet + 확정된 maker들의 wallet)
14. wallet row lock — (user_id, asset) 오름차순으로 정렬 후 일괄 SELECT FOR UPDATE
15. taker 잔액 재검증 (lock 후 확인)
16. taker 자산 lock (available → locked)
17. order 저장
18. trade 저장
19. order 수량/상태 갱신 (FILLED 또는 CANCELED 시 closed_at = now(), lockedAmount = 0)
20. wallet 정산
21. wallet ledger 기록
22. domain event 기록
23. commit 이후 메모리 오더북 변경
```

### 7.2 주문 취소

취소도 주문 생성과 동일하게 market lock 범위 안에서 처리한다. orderId로 market을 식별한 뒤 market lock을 획득하고, 트랜잭션 완료 후 오더북에서 제거한 뒤 lock을 해제한다.

```text
1.  JWT에서 currentUserId 추출
2.  order 조회로 market_id 확인
3.  market lock 획득
4.  order row lock (SELECT FOR UPDATE)
5.  주문 소유자 검증
6.  주문 상태 검증 (OPEN, PARTIALLY_FILLED만 취소 가능)
7.  wallet row lock (lockedAsset wallet을 (user_id, asset) 오름차순으로 잠금)
8.  remainingQuantity 기준 잔여 locked 해제 (locked → available)
9.  order 상태 CANCELED 변경, lockedAmount = 0, closed_at = now()
10. wallet ledger 기록
11. domain event 기록
12. commit 이후 메모리 오더북에서 제거
13. market lock 해제
```

### 7.3 서버 시작 시 오더북 초기화

```text
1. ACTIVE market 조회
2. market별 메모리 오더북 생성
3. OPEN / PARTIALLY_FILLED 주문 조회
4. sequence 기준으로 오더북 적재
5. 주문 생성/취소 처리 준비
```

---

## 8. 기술 스택

| 구분 | 기술 | 이유 |
|---|---|---|
| Language | Java 21 | LTS |
| Framework | Spring Boot 3.5.x | 현재 프로젝트 기준 |
| ORM | Spring Data JPA | 트랜잭션/락 처리 |
| DB | MySQL 8 | InnoDB row lock 검증 |
| Migration | Flyway | 스키마 버전 관리 |
| Auth | Spring Security + JWT | 로그인 사용자 식별 |
| Test | JUnit5 + Testcontainers | DB 통합 테스트 |
| Metrics | Actuator + Prometheus | 운영 관측 기반 |

---

## 9. 구현 단계

### Phase 1. Auth / Schema / Seed

목표: 사용자 식별과 초기 데이터 기반 구축

- Security/JWT 의존성 추가
- Flyway V1 migration 작성
- `users`, `assets`, `markets`, `wallets`, `order_sequences` seed 작성
  - `order_sequences`: seed market마다 `(market_id, last_sequence=0)` row를 미리 삽입한다. sequence row가 없으면 첫 주문에서 `SELECT FOR UPDATE` 실패로 에러가 발생한다.
- 회원가입 API
- 로그인 API
- 현재 사용자 조회 API

완료 기준:

- 회원가입 시 password hash 저장
- 회원가입 시 ACTIVE 상태인 모든 asset에 대해 available=0, locked=0 wallet이 생성된다 (signup 트랜잭션 안에서 원자적으로 처리)
- 로그인 시 JWT access token 발급
- JWT로 `/api/v1/users/me` 조회 가능
- seed market과 seed wallet 확인 가능

### Phase 2. Wallet / Ledger

목표: 자산 잠금/해제와 원장 기록 기반 구축

- Wallet entity/repository
- WalletLedger entity/repository
- wallet row lock 조회
- available -> locked 이동
- locked -> available 해제
- 정산용 delta 변경
- 지갑/원장 조회 API

완료 기준:

- BUY 주문 lock에 필요한 KRW 잠금 가능
- SELL 주문 lock에 필요한 BTC 잠금 가능
- 모든 wallet 변경이 `wallet_ledgers`에 기록
- 음수 잔고 방지

### Phase 3. Order Create / Cancel

목표: 매칭 없는 주문 생성/취소 완성

- Market 조회/검증
- Order entity/repository
- OrderSequence 발급
- clientOrderId 중복 검증
- BUY/SELL 주문 생성 시 자산 lock
- 주문 취소 시 잔여 lock 해제
- 주문 단건/목록 조회 API

완료 기준:

- BUY 주문 생성 시 KRW locked 증가
- SELL 주문 생성 시 BTC locked 증가
- OPEN 주문 취소 시 locked 해제
- PARTIALLY_FILLED 취소 정책 준비

### Phase 4. OrderBook / Matching

목표: 가격-시간 우선 매칭 구현

- MemoryOrderBook
- OrderBookStore
- MatchingEngine
- 가격 우선 매칭
- sequence 기반 시간 우선 매칭
- 자기 체결 거절
- 오더북 조회 API
- 서버 시작 시 오더북 초기화

완료 기준:

- 가격 우선 테스트 통과
- 시간 우선 테스트 통과
- 오더북에는 OPEN/PARTIALLY_FILLED 주문만 존재

### Phase 5. Trade / Settlement

목표: 체결 저장과 지갑 정산 완성

- Trade entity/repository
- 체결 생성
- 주문 수량/상태 갱신
- BUY 정산
- SELL 정산
- 정산 ledger 기록
- domain event 기록
- 시장 체결 조회 API
- 사용자 fill 조회 API

완료 기준:

- 부분 체결 처리
- 완전 체결 처리
- BUY taker 가격 차이 환불 처리
- seller quote 증가 처리
- 체결 후 wallet/ledger/order/trade 정합성 유지

### Phase 6. Integration Test / Stabilization

목표: MVP 완료 기준 검증

- [TestPlan.md](./TestPlan.md) 기반 통합 테스트
- 잔액 부족 테스트
- 자기 체결 거절 테스트
- 취소 불가 상태 테스트
- 오더북 초기화 테스트
- 조회 API 검증

완료 기준:

- 지갑 음수 0건
- 주문 수량 불변식 유지
- 체결 금액 불변식 유지
- 오더북 불변식 유지
- MVP 정상 시나리오 end-to-end 통과

---

## 10. 후순위 확장

아래 항목은 MVP 구현 완료 후 별도 phase에서 다룬다.

### Idempotency 고도화

1차 MVP에서는 `orders.user_id + client_order_id` unique constraint로 주문 중복을 방지한다.

주문 취소 등 다른 command의 멱등성이 필요해지면 그때 별도 테이블을 추가한다.

### Event Outbox

MVP에서는 `domain_events`를 내부 이벤트 로그로만 사용한다.

`published`, `published_at`, `publish_attempts`는 Kafka/outbox 확장을 위한 예약 필드이며 MVP에서는 외부 발행을 구현하지 않는다.

### Reconciliation / Replay

ledger replay, recovery, redrive는 MVP에서 제외한다.

다만 `wallet_ledgers` 구조는 이후 reconciliation 구현이 가능하도록 append-only로 유지한다.

### Realtime

Kafka, Redis, WebSocket, 서버 분리는 MVP 이후 확장으로 둔다.

---

## 11. 패키지 구조

초기 구현 기준 패키지 구조:

```text
com.coinflow
├── auth
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── market
│   ├── api
│   ├── application
│   └── domain
├── wallet
│   ├── api
│   ├── application
│   └── domain
├── order
│   ├── api
│   ├── application
│   └── domain
├── matching
│   ├── application
│   └── domain
├── trade
│   ├── api
│   ├── application
│   └── domain
├── event
│   ├── application
│   └── domain
└── common
    ├── exception
    ├── money
    ├── security
    └── time
```

입금/출금 패키지는 MVP에서 만들지 않는다.

---

## 12. 이력서 문장 후보

- 단일 인스턴스 환경에서 지정가 주문 생성, 자산 잠금, 가격-시간 우선 매칭, 부분/완전 체결, 취소를 포함한 거래소 코어 백엔드 MVP 구현
- JWT 기반 사용자 식별과 `available/locked` 지갑 모델을 통해 계정별 자산 분리와 주문 잠금을 구현
- `orders.sequence` 기반 시간 우선순위와 메모리 오더북을 사용해 매칭 후보 조회와 호가 조회 모델 구성
- `wallet_ledgers` append-only 원장으로 주문 lock, 취소 release, 체결 정산 이력을 추적 가능하게 설계
- 주문, 체결, 지갑, 원장, 이벤트 로그를 하나의 트랜잭션 경계에서 기록해 자산 정합성을 검증
