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
| `assets` | 자산 정보 |
| `markets` | 시장과 주문 검증 정책 |
| `wallets` | 사용자별 자산 현재 스냅샷 |
| `wallet_ledgers` | 모든 지갑 변동 이력 |
| `order_sequences` | 시장별 주문 sequence |
| `orders` | 주문 상태와 수량 |
| `trades` | 체결 기록 |
| `domain_events` | 내부 이벤트 로그 |
| `idempotency_requests` | API 멱등성 확장용 예약 테이블 |

### 4.2 메모리 오더북은 파생 조회 모델

메모리 오더북은 매칭 후보 조회와 호가 조회를 위한 파생 상태다.

- DB가 source of truth다.
- 트랜잭션 commit 이후에만 메모리 오더북을 변경한다.
- 서버 시작 시 `OPEN`, `PARTIALLY_FILLED` 주문을 DB에서 읽어 초기화한다.

### 4.3 동일 시장 명령은 순차 처리

같은 market의 주문 생성/취소/매칭은 순차 처리한다.

MVP 구현 선택지:

- 1차 단순 구현: 단일 lock으로 주문 처리 직렬화
- 이후 개선: market 단위 queue/worker

처음 구현에서는 성능보다 정합성을 우선한다.

### 4.4 원장은 append-only

`wallet_ledgers`는 수정/삭제하지 않는다.

필수 필드:

| 필드 | 의미 |
|---|---|
| `delta_available` | available 변화량 |
| `delta_locked` | locked 변화량 |
| `available_balance_after` | 변경 후 available |
| `locked_balance_after` | 변경 후 locked |
| `reference_type` | `SYSTEM`, `ORDER`, `TRADE` |
| `reference_id` | 참조 ID |
| `order_id` | 관련 주문 ID |
| `trade_id` | 관련 체결 ID |

---

## 5. 도메인 모델 요약

### User

```text
id, email, password_hash, nickname, status
```

### Asset

```text
code, name, display_name, asset_type, precision_unit, min_size, status
```

### Market

```text
symbol, base_asset, quote_asset,
tick_size, step_size, min_order_quantity, min_order_amount,
status, cancel_only
```

### Wallet

```text
user_id, asset, available_balance, locked_balance, version
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
reference_type, reference_id, order_id, trade_id
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
```

취소된 주문도 위 수량 불변식은 유지한다. `CANCELED` 주문의 `remaining_quantity`는 취소된 잔여 수량이다.

### Trade

```text
quote_amount = price * quantity
```

### OrderBook

```text
오더북 포함: OPEN, PARTIALLY_FILLED
오더북 제외: FILLED, CANCELED
```

---

## 7. 처리 흐름

### 7.1 주문 생성

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

### 7.2 주문 취소

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
- `users`, `assets`, `markets`, `wallets` seed 작성
- 회원가입 API
- 로그인 API
- 현재 사용자 조회 API

완료 기준:

- 회원가입 시 password hash 저장
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

`idempotency_requests`는 API command 단위 멱등성 확장을 위한 예약 테이블이다. 1차 구현 필수 대상이 아니다.

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
