# CoinFlow Phase 2 / Load Test Reference

이 문서는 CoinFlow의 현재 단계에 맞는 추가 레퍼런스를 정리한다.

현재 단계는 고성능 매칭 엔진으로 구조를 바꾸는 단계가 아니라, 다음 범위를 완료하고 한계를 문서화하는 단계다.

- Outbox 기반 Kafka 발행
- Kafka Consumer 기반 WebSocket 체결/오더북 전파
- k6/Grafana/Micrometer 기반 부하 측정
- 단일 market 주문 생성 처리량 한계 정리

따라서 이 문서의 레퍼런스는 고성능 엔진 구현보다 **이벤트 전파 신뢰성, market 단위 ordering, WebSocket market data 설계, 부하 테스트 판정 기준**에 초점을 둔다.

---

## 1. 지금 단계에서 차용할 구조

| 주제 | 지금 차용할 것 | 아직 차용하지 않을 것 |
|---|---|---|
| Kafka ordering | `marketSymbol` key로 같은 market 이벤트를 같은 partition에 보내는 구조 | matching command 자체를 Kafka partition worker로 처리 |
| Outbox | DB transaction 안에 이벤트 저장 후 별도 publisher가 Kafka 발행 | DB log tailing, CDC 기반 relay |
| Consumer reliability | at-least-once 전파와 idempotency 필요성 명시 | 모든 consumer exactly-once 처리 보장 |
| WebSocket market data | trade feed, orderbook snapshot, coalescing, p95/p99 lag 측정 | sequence 기반 delta book, checksum, gap recovery 완성 |
| STOMP broker | Spring simple broker로 로컬/단일 인스턴스 부하 기준선 확보 | 외부 STOMP broker relay, broker clustering |
| Load test | threshold, p95/p99, dropped iterations, WS message count 기록 | production capacity claim |

---

## 2. Kafka ordering / delivery semantics

### 레퍼런스

- Apache Kafka Introduction  
  https://kafka.apache.org/11/getting-started/introduction/
- Apache Kafka Design  
  https://kafka.apache.org/40/design/design/

### 확인한 포인트

Kafka는 topic 안의 partition을 병렬 처리 단위로 사용한다.
같은 partition 안에서는 producer가 보낸 순서대로 log에 append되고, consumer는 log에 저장된 순서대로 record를 본다.
consumer group에서는 하나의 partition을 한 consumer가 담당하므로 partition 단위 ordering을 유지할 수 있다.

Kafka producer는 key를 통해 semantic partitioning을 할 수 있다.
즉 같은 key를 같은 partition으로 보내면, 그 key 범위에서는 순서 보장과 병렬 처리의 경계를 설계할 수 있다.

Kafka의 기본 소비/처리 모델은 at-least-once에 가깝다.
Kafka 내부 topic-to-topic 처리에서는 transaction producer와 offset commit을 함께 다루는 exactly-once 구성이 가능하지만, DB나 WebSocket 같은 외부 시스템까지 포함하면 consumer idempotency가 필요하다.

### CoinFlow 적용

현재 CoinFlow는 `OutboxPublisher`에서 Kafka message key로 `marketSymbol`을 사용한다.
이 선택은 같은 market의 order/trade 이벤트를 같은 partition으로 보내 순서가 섞일 가능성을 줄이기 위한 설계다.

현재 문서에서 이렇게 말하는 것이 정확하다.

```text
Kafka는 marketSymbol key를 사용해 같은 market 이벤트의 partition ordering을 의도했다.
다만 WebSocket consumer와 클라이언트까지 포함한 end-to-end exactly-once를 보장하지는 않는다.
중복 수신 가능성이 있으므로 eventId 기반 dedup은 후속 과제다.
```

---

## 3. Transactional Outbox

### 레퍼런스

- Microservices.io Transactional Outbox Pattern  
  https://microservices.io/patterns/data/transactional-outbox.html

### 확인한 포인트

서비스가 DB 상태 변경과 message broker 발행을 함께 해야 할 때, DB와 broker를 2PC로 묶는 것은 보통 바람직하지 않거나 불가능하다.
Outbox는 business DB transaction 안에 message를 먼저 저장하고, 별도 relay가 그 message를 broker로 발행하는 방식이다.

이 패턴은 DB transaction이 commit된 경우에만 message가 발행되도록 만들 수 있다.
반대로 relay가 broker에 발행한 뒤 published 상태를 DB에 기록하기 전에 죽으면 같은 message가 다시 발행될 수 있다.
따라서 consumer는 중복 처리를 감안해야 한다.

### CoinFlow 적용

현재 CoinFlow는 다음 구조를 이미 사용한다.

```text
OrderService transaction
  -> orders / trades / wallets / wallet_ledgers 저장
  -> domain_events 저장
  -> commit
  -> OutboxPublisher polling
  -> Kafka publish
  -> published=true
```

현재 단계의 적절한 결론은 다음이다.

```text
CoinFlow는 DB commit과 Kafka 발행 사이의 이벤트 유실을 줄이기 위해 polling publisher 방식의 Transactional Outbox를 적용했다.
발행은 at-least-once로 보고, WebSocket 중복 수신과 DB write consumer dedup은 후속 범위로 둔다.
```

후속 구현 후보:

- `processed_events(event_id, consumer)` 기반 consumer idempotency 적용
- Kafka listener 실패 시 retry/DLT 구성
- dead letter event 운영 조회/재처리 문서화

---

## 4. Spring WebSocket / STOMP broker

### 레퍼런스

- Spring Framework STOMP External Broker  
  https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html

### 확인한 포인트

Spring의 simple broker는 시작하기 쉽지만, STOMP command 일부만 지원하고 clustering에 적합하지 않다.
확장성이 필요하면 RabbitMQ, ActiveMQ 같은 외부 broker로 relay하는 구조를 사용할 수 있다.

### CoinFlow 적용

현재 CoinFlow는 `enableSimpleBroker("/topic")`를 사용한다.
이 구조는 로컬 단일 인스턴스에서 WebSocket 전파 경로와 병목을 검증하는 현재 단계에는 충분하다.

다만 문서에서는 다음 한계를 명확히 써야 한다.

```text
현재 WebSocket은 Spring simple broker 기반의 단일 인스턴스 실시간 전파 검증이다.
다중 서버 WebSocket fan-out, session 분산, broker clustering은 아직 구현 범위가 아니다.
```

후속 구현 후보:

- STOMP broker relay 도입
- WebSocket 인증/권한 적용
- market별 topic 권한 정책
- client reconnect 후 snapshot 재동기화

---

## 5. Kafka consumer error handling / DLT

### 레퍼런스

- Spring Kafka Handling Exceptions  
  https://docs.spring.io/spring-kafka/docs/3.1.x/reference/kafka/annotation-error-handling.html

### 확인한 포인트

Spring Kafka는 listener exception을 다루기 위한 error handler, retry, dead letter publishing 구성을 제공한다.
실패 record를 retries 이후 DLT로 보내는 구조를 만들 수 있고, listener 처리 실패를 관측 가능한 운영 이벤트로 분리할 수 있다.

### CoinFlow 적용

현재 WebSocket broadcaster는 Kafka listener 안에서 파싱 실패나 broadcast 실패를 잡고 warning log로 남긴다.
WebSocket push는 DB 상태를 변경하지 않는 consumer라서 현재 단계에서는 큰 문제는 아니다.

하지만 후속 consumer가 DB에 projection, 통계, 정산 결과를 쓰게 되면 다음이 필요하다.

- listener 실패 retry 정책
- DLT topic
- `eventId + consumer` dedup
- 실패 event 운영 조회 및 replay 절차

현재 단계 문서 표현:

```text
Phase 2 WebSocket consumer는 best-effort broadcast 계층이다.
DB projection consumer를 추가하기 전에는 DLT와 idempotent consumer를 후속 과제로 둔다.
```

---

## 6. Exchange WebSocket market data patterns

### 레퍼런스

- Binance Spot WebSocket Streams  
  https://github.com/binance/binance-spot-api-docs/blob/master/web-socket-streams.md
- Coinbase Exchange WebSocket Channels  
  https://docs.cdp.coinbase.com/exchange/websocket-feed/channels
- Kraken WebSocket v2 Book  
  https://docs.kraken.com/api/docs/websocket-v2/book/

### 확인한 포인트

실제 거래소의 orderbook stream은 단순히 full snapshot을 반복 전송하는 방식으로 끝나지 않는다.
공통적으로 다음 요소가 나온다.

- REST snapshot + WebSocket update 결합
- update id 또는 sequence number
- 누락 감지
- 누락 발생 시 snapshot 재조회
- price level quantity가 `0`이면 level 제거
- checksum으로 local book 정합성 검증

Binance는 depth snapshot의 `lastUpdateId`와 WebSocket depth event의 update id 범위를 비교해 gap을 감지한다.
Coinbase는 WebSocket message를 queue한 뒤 REST snapshot sequence와 맞춰 replay한다.
Kraken은 book snapshot/update에 checksum을 포함해 top levels 정합성을 확인할 수 있게 한다.

### CoinFlow 적용

현재 CoinFlow는 다음 수준까지 구현했다.

- Kafka order event 수신
- 현재 in-memory orderbook snapshot 생성
- market별 `/topic/orderbook/{market}` broadcast
- coalescing으로 full snapshot 전파 빈도 감소

현재 단계에서는 이것을 이렇게 설명하는 것이 적절하다.

```text
CoinFlow Phase 2의 orderbook WebSocket은 full snapshot broadcast 기준선이다.
실제 거래소식 delta stream은 아직 구현하지 않았고, 현재 부하 테스트는 snapshot broadcast의 fan-out 비용과 coalescing 효과를 검증하는 목적이다.
```

후속 구현 후보:

- orderbook message에 monotonic `bookSequence` 추가
- `snapshot + delta` protocol 분리
- gap detection 기준 문서화
- reconnect 시 REST snapshot 조회 후 delta replay
- checksum 또는 top-N hash 도입

---

## 7. k6 load test 판정 기준

### 레퍼런스

- k6 Thresholds  
  https://grafana.com/docs/k6/latest/using-k6/thresholds/
- k6 Built-in metrics  
  https://grafana.com/docs/k6/latest/using-k6/metrics/reference/
- k6 WebSockets  
  https://grafana.com/docs/k6/latest/using-k6/protocols/websockets/

### 확인한 포인트

k6 threshold는 metric 기준을 만족하지 못하면 test failure로 판단한다.
WebSocket 테스트에서는 HTTP와 달리 연결 후 event loop 안에서 message 수신, ping, session duration 등을 관측한다.
`dropped_iterations`는 arrival-rate executor에서 충분한 VU가 없거나 시간이 부족해 시작하지 못한 iteration 수를 의미한다.

### CoinFlow 적용

현재 CoinFlow 부하 테스트 결과는 다음 지표를 같이 봐야 한다.

| 지표 | 의미 |
|---|---|
| `order_create_duration p95/p99` | 주문 생성 latency 안정성 |
| `ws_trade_delivery_lag p95/p99` | 체결 발생 후 WebSocket 수신 지연 |
| `dropped_iterations` | 요청 rate를 실제로 소화하지 못한 정도 |
| `http_req_failed`, 5xx | 기능 실패 여부 |
| Kafka consumer lag | Kafka consumer backlog 여부 |
| Outbox unpublished events | 발행 backlog 여부 |
| Hikari active/pending | DB connection pressure |
| `order.create.stage.duration` | 내부 병목 구간 분해 |

현재 문서 결론은 다음처럼 쓰는 것이 정확하다.

```text
HTTP failed와 5xx가 0이어도 dropped_iterations와 p95 latency가 커지면 안정 처리량을 넘은 것이다.
CoinFlow의 현 구조에서는 WebSocket/Kafka 전파보다 단일 market 주문 생성 직렬화가 먼저 포화된다.
```

---

## 8. 현재 단계의 이력서/면접 표현

좋은 표현:

```text
Outbox/Kafka/WebSocket 기반 실시간 전파 경로를 구현하고,
k6/Grafana/Micrometer로 trade delivery lag, Kafka lag, Outbox backlog,
order create p95/p99, dropped iterations를 측정해 병목을 분리했습니다.
```

```text
WebSocket/Kafka 전파 병목은 Outbox cadence 조정과 orderbook broadcast coalescing으로 완화했고,
이후 병목이 단일 market 주문 생성 직렬화로 이동함을 측정으로 확인했습니다.
```

위험한 표현:

```text
고성능 거래소 엔진을 구현했습니다.
분산 환경에서도 주문 순서를 보장합니다.
Kafka/WebSocket exactly-once 전파를 보장합니다.
실제 거래소 수준의 orderbook delta protocol을 구현했습니다.
```

대신 이렇게 말한다.

```text
현재는 단일 인스턴스 거래소 코어와 실시간 전파 기준선을 만든 단계입니다.
다음 단계에서는 market worker, append-only journal, delta orderbook, idempotent consumer로 확장할 계획입니다.
```

---

## 9. 후속 설계 후보

현재 단계의 다음 개선은 두 갈래로 나누는 것이 좋다.

### 9-1. Phase 2 마감 품질 개선

- README와 interview 문서의 구현 상태 불일치 정리
- WebSocket/Kafka 부하 테스트 결과 표준 템플릿 고정
- consumer 중복 수신 한계 명시
- simple broker 한계 명시
- fresh DB/Kafka 기준 부하 테스트 재실행 절차 문서화

### 9-2. v3 고성능 구조 검토

- market별 command queue / worker
- order command journal
- matching result journal
- DB projection 비동기화
- price level 기반 orderbook 자료구조
- orderbook delta + sequence + snapshot recovery
- idempotent consumer와 replay/redrive

이 중 지금 바로 구현할 것은 9-1이고, 9-2는 현재 기준선을 마감한 뒤 별도 브랜치/문서에서 진행한다.
