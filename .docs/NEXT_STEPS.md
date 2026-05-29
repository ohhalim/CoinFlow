# CoinFlow 후속 작업

README는 현재까지의 문제 해결 과정과 측정 결과 중심으로 유지한다. 후속 작업 후보와 구현 범위는 이 문서에서 관리한다.

## 현재 완료 기준

- Phase 1 거래 코어 구현
- 주문, 체결, 지갑, 원장 정합성 테스트 보강
- Phase 2 Outbox/Kafka/WebSocket 외부 전파 구현
- WebSocket/Kafka 전파 병목 측정 및 개선
- 오더북 broadcast lock 경합 개선
- 단일 market 주문 생성 병목 분리
- `202 Accepted` 비동기 주문 접수 응답 분리

## 후속 작업 후보

| 항목 | 목적 |
|---|---|
| 주문 생성 흐름 OOP 리팩토링 | 주문 접수, 검증, 자산 잠금, 매칭, 정산, 이벤트 저장 책임 분리 |
| 비동기 주문 처리 완료 latency 측정 | `202 Accepted` 응답 이후 worker 완료까지의 실제 처리 지연 분리 |
| worker backlog 기준선 정리 | `order.command.queue.depth`, `command_queue_wait`, `command_worker_process` 기준선 관리 |
| in-memory matching / async persistence 전환 기준 문서화 | DB I/O가 worker 처리량 한계로 남는 경우의 다음 구조 기준 정리 |
| WebSocket 연결 인증/권한 분리 | 사용자별 구독 권한과 인증 경계 분리 |
| 정산 Batch 추가 | 반복적인 정산/원장 저장 비용 절감 가능성 검토 |

## 우선순위

| 순서 | 작업 | 이유 |
|---|---|---|
| 1 | 주문 생성 흐름 OOP 리팩토링 | 현재 주문 생성 경로가 기능 추가와 성능 분석을 모두 흡수해 코드 가독성 저하 |
| 2 | 비동기 주문 완료 latency 측정 | HTTP 응답 지연과 실제 체결 완료 지연 분리 |
| 3 | worker backlog 기준선 정리 | async 전환 이후 잔여 병목 수치화 |
| 4 | in-memory matching / async persistence 설계 | 단일 market worker 처리량 한계 이후 구조 전환 기준 확보 |

## 완료 기능으로 표기하지 않는 범위

- WebSocket 인증/권한 분리
- 정산 Batch
- in-memory matching engine
- async persistence
- replay / redrive / reconciliation
