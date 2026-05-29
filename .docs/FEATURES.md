# CoinFlow 상세 구현 범위

README는 문제 정의, 측정 결과, 개선 과정 중심으로 유지한다. 세부 기능과 검증 범위는 이 문서에서 관리한다.

## 핵심 기능

### 1. 주문, 매칭, 정산 코어

- 지정가 `BUY` / `SELL` 주문 생성
- `201 Created` 동기 주문 생성과 `202 Accepted` 비동기 주문 접수
- 가격 우선, 시간 우선 매칭
- 부분 체결, 완전 체결
- 주문 취소
- BUY 주문 quote asset 잠금, SELL 주문 base asset 잠금
- 체결 시 buyer/seller 지갑 정산
- BUY taker 가격 차이 환불
- append-only 지갑 원장 기록
- worker 실패 시 `REJECTED` 상태 전이와 locked asset 해제
- 서버 시작 시 DB의 미체결 주문으로 인메모리 오더북 초기화

### 2. 조회 API

- 시장 조회
- 오더북 조회
- 최근 체결 조회
- 사용자 fill 조회
- 지갑 조회
- 지갑 원장 조회

### 3. 이벤트 기반 실시간 전파

- 주문/체결/정산 도메인 이벤트를 `domain_events` outbox에 저장
- Outbox Publisher가 DB commit 이후 Kafka topic으로 발행
- Kafka 발행 성공/실패 상태와 재시도 횟수 관리
- Kafka Consumer 기반 WebSocket 실시간 체결 push
- Kafka Consumer 기반 WebSocket 오더북 snapshot push

| 항목 | 값 |
|---|---|
| WebSocket endpoint | `ws://localhost:8080/ws` |
| 체결 feed topic | `/topic/trades/{market}` |
| 체결 예시 topic | `/topic/trades/BTC-KRW` |
| 오더북 feed topic | `/topic/orderbook/{market}` |
| 오더북 예시 topic | `/topic/orderbook/BTC-KRW` |

## 주요 검증 범위

- 회원가입, 로그인, JWT 인증
- BUY/SELL 주문 자산 잠금
- 가격 우선, 시간 우선 매칭
- 부분 체결, 완전 체결
- BUY taker 가격 차이 환불
- SELL taker 정산
- 부분 체결 후 취소
- 자기 체결 거절
- 원장 기록
- 오더북 조회
- 도메인 이벤트 저장
- Outbox Publisher Kafka 발행
- Kafka 발행 실패 시 outbox 재시도 상태 전이
- Kafka Consumer 기반 WebSocket 체결 알림
- WebSocket STOMP 실제 수신 E2E
- Kafka Consumer 기반 WebSocket 오더북 snapshot broadcast
- 지갑 잔고 음수 방지
- 동일 사용자 동시 주문 시 잔고 음수 방지
- 하나의 maker 주문에 대한 동시 taker 체결 수량 초과 방지
- 주문 처리 중 오더북 반복 조회 안정성
- 주문 취소와 체결 경합 시 최종 상태 정합성
- k6 기반 주문/조회 API 로컬 부하 테스트
- k6 기반 WebSocket/Kafka 실시간 전파 부하 테스트
- k6 기반 sync 201 / async 202 주문 응답 지연 비교

## 실행 명령

전체 테스트:

```bash
./gradlew test
```

k6 부하 테스트:

```bash
k6 run k6/order-flow-load-test.js
k6 run k6/websocket-kafka-load-test.js
```

## 구현 범위

- 회원가입, 로그인, JWT access token 인증
- 사용자별 지갑 자동 생성 및 데이터 분리
- 지정가 `BUY` / `SELL` 주문 생성
- 주문 취소
- 가격 우선, 시간 우선 매칭
- 부분 체결, 완전 체결
- 체결 시 buyer/seller 지갑 정산
- append-only 지갑 원장 기록
- 시장, 오더북, 최근 체결, 사용자 fill, 지갑, 원장 조회
- 서버 시작 시 DB의 미체결 주문으로 인메모리 오더북 초기화
- 주문/체결/정산 도메인 이벤트 로그 저장
- Outbox Publisher 기반 Kafka 이벤트 발행
- Kafka Consumer 기반 WebSocket 실시간 체결 push
- Kafka Consumer 기반 WebSocket 오더북 snapshot push
- `202 Accepted` 비동기 주문 접수 API
- 비동기 주문 worker 실패 시 `REJECTED` 상태 전이와 locked asset 해제

## 제외 범위

- 입금/출금
- 시장가 주문
- IOC/FOK/GTT, post-only, iceberg 주문
- 수수료
- refresh token, OAuth/social login, role/permission
- WebSocket 연결 인증/권한 분리
- Redis, 서버 분리
- replay, redrive, reconciliation
- 관리자 페이지

일부 로컬 개발 편의를 위한 API와 인프라 기반은 존재하지만, 운영 기능 범위와 구분한다. 예를 들어 dev/test 입금 보조 API는 `prod` 프로필에서 제외된다.
