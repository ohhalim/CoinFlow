# CoinFlow - 프로젝트 기안서 v2
## 단일 서버 기반 고정합성 주문 매칭 및 자산 원장 시스템
### 부제: BTC/KRW 단일 마켓 암호화폐 거래소 코어

---

## 1. 한 줄 정의

> 단일 서버 환경에서 BTC/KRW 단일 마켓의 주문 접수, 자산 잠금, 가격·시간 우선 매칭, 부분 체결,
> append-only ledger, 정합성 검증까지 직접 구현하고 수치로 증명하는 백엔드 프로젝트

---

## 2. 프로젝트 목표

| 증명 항목 | 구현 방법 |
|---|---|
| 거래 핵심 로직 직접 설계 | 외부 PG/API 의존 없음 |
| 자산 잠금 모델 | `available / locked` 분리 |
| 매칭 우선순위 보장 | 가격 우선 / 시간 우선 / 부분 체결 |
| 중복 처리 방지 | DB 기반 idempotency |
| 자산 이동 추적 | append-only ledger |
| 정합성 재검증 | ledger replay reconciliation |
| 성능 측정 및 개선 | k6 + Prometheus + Grafana |

---

## 3. 범위

### 포함
- BTC/KRW 단일 마켓
- 지정가 매수 / 지정가 매도
- 주문 생성 / 취소 / 체결
- 부분 체결
- wallet / ledger 기반 자산 관리
- 체결 내역 조회
- 기본 orderbook 조회
- ledger replay 정합성 검증
- k6 부하 테스트 및 수치 확보

### 제외 (MVP)
- 실제 블록체인 입출금
- 시장가 주문
- 다중 마켓
- 자동매매 봇
- 분산 매칭 엔진
- Kafka / WebSocket
- 복잡한 인증/권한 모델

### Phase 4 확장
- `outbox -> Kafka -> WebSocket` 실시간 호가창/체결 broadcast

---

## 4. 핵심 설계 원칙

### 4.1 DB가 진실이다 (MySQL = Source of Truth)

MVP에서는 MySQL이 유일한 source of truth다.

| 테이블 | 역할 |
|---|---|
| `wallets` | 현재 상태 스냅샷 |
| `ledger_entries` | 모든 자산 이동 이력 (append-only) |
| `orders` | 주문 이력 |
| `trades` | 체결 이력 |
| `idempotency_requests` | 중복 요청 방지 및 결과 재반환 |

Redis는 MVP에서 제외. 금융성 정합성이 먼저다.

### 4.2 단일 마켓 = 단일 Coordinator

BTC/KRW는 한 개 마켓이므로 주문 생성/취소/매칭으로 이어지는 상태 변경 구간은
같은 `MarketCoordinator`를 통과시킨다.

- 구현: 단일 JVM의 `ReentrantLock` 또는 single-thread executor
- 이유: 단일 서버 MVP에서 가장 단순하게 price-time priority와 cancel/fill race를 보장

### 4.3 시간 우선은 sequence로 보장

동일 가격 내 우선순위는 `created_at`이 아니라 단조 증가하는 `priority_seq`로 보장한다.

- 동일 가격일 때 `priority_seq ASC`
- 이 디테일 하나로 프로젝트가 훨씬 진짜 같아진다

### 4.4 Ledger는 append-only

`ledger_entries`는 수정/삭제하지 않는다.

| 필드 | 설명 |
|---|---|
| `wallet_id` | 대상 지갑 |
| `entry_type` | 이동 유형 |
| `delta_available` | available 변화량 |
| `delta_locked` | locked 변화량 |
| `available_after` | 이동 후 available |
| `locked_after` | 이동 후 locked |
| `reference_type` | 참조 유형 (ORDER / TRADE / CANCEL) |
| `reference_id` | 참조 ID |
| `request_id` | idempotency key |

### 4.5 Reconciliation이 킬러 기능

"이력이 남아요" < "이력으로 현재 상태를 재검증할 수 있어요"

배치 job이 ledger를 replay해서 wallet 잔고를 다시 계산하고, 현재 wallet 스냅샷과 비교한다.

---

## 5. 도메인 모델

### Wallet
```
user_id, asset_code, available_balance, locked_balance, version
unique (user_id, asset_code)
```

### Order
```
order_id, user_id, market, side, price,
original_quantity, remaining_quantity, status, priority_seq
```

### Trade
```
trade_id, buy_order_id, sell_order_id, price, quantity, executed_at
```

### LedgerEntry
```
ledger_id, wallet_id, entry_type,
delta_available, delta_locked, available_after, locked_after,
reference_type, reference_id, request_id
```

### IdempotencyRequest
```
request_id, user_id, command_type, request_key,
status, response_snapshot
unique (user_id, command_type, request_key)
```

---

## 6. 불변조건

### Wallet
- `available_balance >= 0`
- `locked_balance >= 0`
- `available_balance + locked_balance = total_balance`

### Order
- `remaining_quantity >= 0`
- 동일 주문은 중복 FILLED 불가
- 취소는 `OPEN`, `PARTIALLY_FILLED`만 가능

### Trade
- 체결 수량은 양측 `remaining_quantity` 초과 불가
- 체결 시 buyer/seller 자산 이동은 ledger에 반드시 기록

### Idempotency
- 동일 `request_key`는 1회만 성공 처리
- 재시도 시 최초 결과 그대로 반환

### Reconciliation
- ledger replay 결과와 wallet 스냅샷 차이 = 0

---

## 7. 처리 흐름

### 7.1 주문 생성
```
1. idempotency_requests 중복 확인
2. MarketCoordinator 진입 (lock 획득)
3. 사용자 wallet 조회
4. 매수면 KRW, 매도면 BTC의 available 검사
5. available -> locked 이동
6. ledger 기록
7. 주문 생성 (OPEN)
8. 반대 side 최우선 주문부터 매칭 시도
9. trade 생성, 양측 wallet 반영, ledger 기록
10. 주문 상태를 OPEN / PARTIALLY_FILLED / FILLED로 갱신
11. idempotency result snapshot 저장 후 반환
```

### 7.2 주문 취소
```
1. idempotency_requests 확인
2. MarketCoordinator 진입 (lock 획득)
3. OPEN / PARTIALLY_FILLED만 취소 허용
4. remaining_quantity 기준으로 locked 자산 복원
5. ledger 기록
6. 상태를 CANCELED로 변경
```

### 7.3 Reconciliation
```
1. 특정 user + asset 또는 전체 계정 대상
2. ledger를 시간순 replay
3. available / locked 재계산
4. wallet 스냅샷과 비교
5. mismatch 발생 시 로그/알림/테스트 실패 처리
```

---

## 8. 기술 스택

| 구분 | 기술 | 이유 |
|---|---|---|
| Language | Java 21 | 최신 LTS |
| Framework | Spring Boot 3.4.x | 트랜잭션/테스트/운영 편의 |
| ORM | Spring Data JPA | 낙관적 락, 트랜잭션 경계 제어 |
| DB | MySQL 8 | InnoDB 락 실험 가능 |
| Migration | Flyway | 스키마 버전 관리 |
| Test | JUnit5 + Testcontainers | 통합 테스트와 정합성 검증 |
| Load Test | k6 | mixed order/cancel 시나리오 실측 |
| Metrics | Prometheus + Grafana | p95, error rate, DB connection 관측 |
| Infra | Docker Compose | 단일 서버 실험 환경 |

### 의도적으로 제외한 것
- WebFlux - MVP correctness를 흐리지 않기 위해
- Kafka - Phase 4로 분리
- WebSocket - Phase 4로 분리
- Redis 중심 설계 - DB를 source of truth로 고정

---

## 9. 성능 및 검증 목표

### 정합성 목표
- 동시 주문/취소 혼합 1,000건 기준 잔고 음수 0건
- duplicate fill 0건
- idempotency 재시도 오류 0건
- ledger replay mismatch 0건

### 성능 목표
- 주문 생성 TPS >= 200
- 주문 생성 p95 <= 500ms
- mixed order/cancel 오류율 < 1%
- 매칭 구간 병목 원인 문서화 완료

### 필수 테스트
- 동일 요청 재시도 테스트 (idempotency)
- 같은 가격 주문의 priority_seq 검증 테스트
- 부분 체결 테스트
- cancel vs fill race 테스트
- ledger replay 정합성 테스트

---

## 10. 구현 단계

### Phase 1. Wallet / Ledger / Idempotency
**목표:** 자산 관리 기반 구축

- user, wallet, deposit API
- append-only ledger 구현
- idempotency_requests 구현
- 지갑 불변조건 테스트

**완료 기준:**
- 음수 잔고 재현 케이스 차단
- request 재시도 시 동일 결과 반환

---

### Phase 2. Matching Engine
**목표:** 거래소 코어 구현

- order create / cancel
- MarketCoordinator (ReentrantLock)
- price-time priority (priority_seq)
- partial fill
- trade 반영 + wallet + ledger

**완료 기준:**
- 동시 주문/취소 시나리오에서 duplicate fill 0
- cancel/fill race 테스트 통과

---

### Phase 3. Measurement / Reconciliation
**목표:** 수치 증명 + 정합성 재검증

- k6 mixed scenario
- Prometheus / Grafana 대시보드
- ledger replay reconciliation job
- 병목 분석 및 개선

**완료 기준:**
- 이력서에 넣을 수 있는 수치 2개 이상 확보
- reconciliation mismatch 0

---

### Phase 4. Optional Expansion
**목표:** 실시간 피드 확장

- outbox pattern
- Kafka
- WebSocket orderbook / recent trades broadcast

**완료 기준:**
- MVP와 독립적으로 설명 가능

---

## 11. Spring Initializr 설정

### 기본 설정
| 항목 | 값 |
|---|---|
| Project | Gradle - Groovy |
| Language | Java |
| Spring Boot | 3.4.x |
| Group | `com.coinflow` |
| Artifact | `coinflow` |
| Package name | `com.coinflow` |
| Packaging | Jar |
| Java | 21 |

### Dependencies
| 분류 | 의존성 | 용도 |
|---|---|---|
| Web | Spring Web | REST API |
| Data | Spring Data JPA | ORM |
| Data | Flyway Migration | 스키마 버전 관리 |
| Data | MySQL Driver | DB 드라이버 |
| Validation | Validation | `@Valid`, `@NotNull` |
| Ops | Spring Boot Actuator | Prometheus 메트릭 |
| Dev | Lombok | 보일러플레이트 제거 |

> WebSocket, Redis, Security는 Phase 4 또는 필요 시 추가

---

## 12. 패키지 구조

```
com.coinflow
├── account          # 사용자, 지갑, 입금
│   ├── domain
│   ├── application
│   └── api
├── order            # 주문 생성/취소
│   ├── domain
│   ├── application
│   └── api
├── matching         # MarketCoordinator, 매칭 엔진
│   ├── domain
│   └── application
├── trade            # 체결 기록, 조회
│   ├── domain
│   └── api
├── ledger           # append-only ledger, reconciliation
│   ├── domain
│   └── application
└── common           # 공통 예외, 응답, idempotency
```

---

## 13. 이력서 문장 (완성 후)

- 단일 서버 환경에서 BTC/KRW 단일 마켓의 주문 생성, 자산 잠금, 부분 체결, 취소를 포함한 거래소 코어 백엔드 구현
- `available/locked` 분리와 append-only ledger 설계를 통해 자산 이동 이력을 추적 가능하게 구성
- 단일 마켓 coordinator와 `priority_seq`를 통해 가격·시간 우선 매칭과 cancel/fill race를 통제
- DB 기반 idempotency와 ledger replay reconciliation으로 중복 요청 및 정합성 검증 구조 구현
- k6, Prometheus, Grafana 기반 mixed order/cancel 시나리오 측정 및 병목 개선
