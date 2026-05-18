# CoinFlow

CoinFlow는 단일 인스턴스 환경에서 지정가 주문 생성, 가격-시간 우선 매칭, 체결, 지갑 정산, 원장 기록까지 검증하는 암호화폐 거래소 코어 백엔드 MVP입니다.

이 프로젝트는 실시간 시세나 분산 인프라보다 주문과 자산 정합성을 우선합니다. 주문이 체결될 때 `orders`, `trades`, `wallets`, `wallet_ledgers`, `domain_events`가 일관되게 기록되는 것을 목표로 합니다.

## 구현 범위

- 회원가입, 로그인, JWT access token 인증
- 사용자별 지갑 자동 생성 및 데이터 분리
- 지정가 `BUY` / `SELL` 주문 생성
- 주문 취소
- 가격 우선, 시간 우선 매칭
- 부분 체결, 완전 체결
- BUY 주문 quote asset 잠금, SELL 주문 base asset 잠금
- 체결 시 buyer/seller 지갑 정산
- append-only 지갑 원장 기록
- 시장, 오더북, 최근 체결, 사용자 fill, 지갑, 원장 조회
- 서버 시작 시 DB의 미체결 주문으로 인메모리 오더북 초기화
- 주문/체결/정산 도메인 이벤트 로그 저장

## 제외 범위

- 입금/출금
- 시장가 주문
- IOC/FOK/GTT, post-only, iceberg 주문
- 수수료
- refresh token, OAuth/social login, role/permission
- Kafka 기반 이벤트 발행
- WebSocket 실시간 체결/호가 push
- Redis, MQ, 서버 분리
- replay, redrive, reconciliation
- 관리자 페이지

일부 로컬 개발 편의를 위한 API와 인프라 기반은 존재하지만, 운영 기능 범위와 구분합니다. 예를 들어 dev/test 입금 보조 API는 `prod` 프로필에서 제외되며, Kafka 컨테이너는 로컬 인프라 기반일 뿐 현재 애플리케이션 코드에는 `spring-kafka` producer/consumer가 연결되어 있지 않습니다.

Phase 1 완료 이후 리뷰 과정에서 zero-quote 체결 방지, dust maker 자동 취소, 오더북 재빌드 같은 정합성 보강이 추가되었습니다.

## 핵심 설계

| 주제 | 설계 |
|---|---|
| Source of truth | DB의 주문, 체결, 지갑, 원장을 기준 상태로 둡니다. |
| 인메모리 오더북 | 매칭 후보 조회와 호가 조회를 위한 파생 상태입니다. |
| 오더북 반영 | DB commit 이후에만 인메모리 오더북을 변경합니다. |
| 순차 처리 | 같은 시장의 주문 생성/취소는 market별 `ReentrantLock`으로 직렬화합니다. |
| DB 동시성 | sequence, wallet, maker order 갱신에 pessimistic lock을 사용합니다. |
| 지갑 모델 | `available_balance`와 `locked_balance`를 분리합니다. |
| 원장 | 모든 지갑 변경을 `wallet_ledgers`에 append-only로 기록합니다. |
| 이벤트 | `domain_events`를 내부 이벤트 로그로 저장하고, 이후 Outbox 확장 경계를 남깁니다. |

## 기술 스택

- Java 21
- Spring Boot 3.5
- Spring Web MVC
- Spring Security + OAuth2 Resource Server + JWT
- Spring Data JPA
- MySQL 8
- Flyway
- JUnit 5, AssertJ
- Testcontainers MySQL
- Actuator, Micrometer, Prometheus registry
- Docker Compose

## 실행 방법

### 1. 로컬 인프라 실행

```bash
docker compose up -d mysql
```

`docker-compose.yml`에는 Kafka 컨테이너도 포함되어 있지만, 현재 MVP 애플리케이션 실행에는 MySQL만 필요합니다.

### 2. 애플리케이션 실행

```bash
./gradlew bootRun
```

기본 DB 접속 정보는 다음과 같습니다.

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/coinflow?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
spring.datasource.username=coinflow
spring.datasource.password=coinflow
```

로컬 실행 기준은 Docker MySQL의 `3306:3306` 포트 매핑입니다. 로컬에 직접 설치된 MySQL이 `3306`을 점유하고 있다면 해당 서비스를 중지하고 Docker MySQL을 사용합니다.

### 3. API 문서

애플리케이션 실행 후 Swagger UI에서 API를 확인할 수 있습니다.

```text
http://localhost:8080/swagger-ui/index.html
```

### 4. 로컬 모니터링

애플리케이션은 기본 설정으로 `/actuator/prometheus`를 노출합니다. Prometheus와 Grafana는 로컬 `bootRun` 애플리케이션을 `host.docker.internal:8080`으로 scrape합니다.

```bash
docker compose up -d prometheus grafana
```

접속 주소:

| 도구 | URL | 기본 계정 |
|---|---|---|
| Prometheus | `http://localhost:9090` | - |
| Grafana | `http://localhost:3000` | `admin` / `admin` |

Grafana에는 `CoinFlow Overview` 대시보드가 자동 등록됩니다.

## 테스트

전체 테스트는 다음 명령으로 실행합니다.

```bash
./gradlew test
```

통합 테스트는 Testcontainers 기반 MySQL을 사용해 decimal, foreign key, transaction 경계와 핵심 정합성 시나리오를 실제 MySQL에 가깝게 검증합니다. 동시성 테스트와 k6 부하 테스트는 [Test Plan](.docs/TestPlan.md)에 계획을 분리해 두고, 실행 결과는 [Test Results](.docs/TEST_RESULTS.md)에 기록합니다.

주요 검증 범위:

- 회원가입, 로그인, JWT 인증
- BUY/SELL 주문 자산 잠금
- 가격 우선 매칭
- 부분 체결, 완전 체결
- BUY taker 가격 차이 환불
- SELL taker 정산
- 부분 체결 후 취소
- 자기 체결 거절
- 원장 기록
- 오더북 조회
- 도메인 이벤트 저장
- 지갑 잔고 음수 방지
- 동일 사용자 동시 주문 시 잔고 음수 방지
- 하나의 maker 주문에 대한 동시 taker 체결 수량 초과 방지
- 주문 처리 중 오더북 반복 조회 안정성
- 주문 취소와 체결 경합 시 최종 상태 정합성
- k6 기반 주문/조회 API 로컬 부하 테스트

k6 로컬 부하 테스트는 애플리케이션 실행 후 다음 명령으로 실행합니다.

```bash
k6 run k6/order-flow-load-test.js
```

## 문서

| 문서 | 설명 |
|---|---|
| [PRD](.docs/PRD.md) | MVP 제품 범위, 포함/제외 기준, 성공 기준 |
| [Plan](.docs/Plan.md) | MVP 구현 순서와 설계 원칙 |
| [API](.docs/API.md) | REST API 계약과 에러 코드 |
| [ERD](.docs/ERD.md) | 테이블 구조와 관계 |
| [Test Plan](.docs/TestPlan.md) | 핵심 통합 테스트, 동시성 테스트, k6 부하 테스트 계획 |
| [Test Results](.docs/TEST_RESULTS.md) | 동시성/k6 테스트 실행 결과 기록 템플릿 |
| [Order Flow](.docs/ORDER_FLOW.md) | 주문 생성부터 체결/정산/오더북 반영까지의 내부 흐름 |
| [Issues](.docs/ISSUES.md) | Phase 1 이후 코드 리뷰 이슈와 보강 내용 |
| [Reference](.docs/Reference.md) | 설계 판단 근거와 외부 거래소 API 레퍼런스 |

## 다음 단계

현재 구현 완료 범위는 Phase 1 MVP입니다. Phase 1 거래 코어의 동시성/부하 테스트와 로컬 관측 구성까지 추가했으며, 이후 이벤트 발행과 실시간 전파를 별도 이슈로 확장합니다.

- 장시간 k6 soak 테스트와 Grafana 관측 결과 기록
- OutboxPublisher 구현
- `domain_events.published=false` 이벤트 Kafka 발행
- Kafka 발행 성공/실패 상태와 재시도 횟수 관리
- Kafka Consumer 기반 WebSocket 체결/오더북 broadcast
- 정산 Batch 추가

Kafka, WebSocket, Batch 정산은 아직 구현 완료 기능으로 표기하지 않습니다.
