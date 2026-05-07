# CoinFlow MVP Reference

이 문서는 CoinFlow MVP 설계 의도를 설명하고 방어하기 위한 레퍼런스 노트다.

구현 계약은 `PRD.md`, `ERD.md`, `API.md`, `TestPlan.md`가 우선한다. 이 문서는 미팅에서 "왜 이렇게 설계했는가"를 설명하기 위한 배경 자료로 사용한다.

---

## 1. MVP 설명의 핵심 문장

CoinFlow MVP는 거래소의 모든 기능을 구현하는 프로젝트가 아니라, 지정가 주문 생성, 자산 잠금, 가격-시간 우선 매칭, 체결, 정산, append-only 원장 기록까지 이어지는 거래소 코어 백엔드 정합성을 검증하는 프로젝트다.

그래서 입금/출금, 시장가 주문, 수수료, WebSocket, Kafka, Redis, 서버 분리, replay/recovery는 MVP에서 제외한다. 초기 잔액은 seed wallet balance로 만들고, 그 잔액 안에서 주문-체결-정산 흐름이 깨지지 않는지 검증한다.

---

## 2. 문서 우선순위

| 문서 | 역할 |
|---|---|
| `PRD.md` | 제품 범위, MVP 포함/제외 기준, 성공 기준 |
| `ERD.md` | DB 모델, FK, 제약조건, 정합성 기준 |
| `API.md` | REST API 계약, request/response, 에러 코드 |
| `TestPlan.md` | 통합 테스트 시나리오와 불변식 |
| `Plan.md` | 구현 순서와 phase별 완료 기준 |
| `Reference.md` | 설계 의도 설명과 외부 참고 자료 |
| `ERDCloud.sql` | ERD Cloud import용 단순 DDL |

---

## 3. 주요 설계 결정과 설명 근거

| 설계 결정 | 설명 |
|---|---|
| 지정가 주문만 MVP에 포함 | 가격과 수량이 명확해서 lock, match, settle, ledger 검증이 단순하고 명확하다. 시장가 주문은 슬리피지, 잔량 처리, 금액 기반 주문 등 추가 정책이 필요하므로 후순위다. |
| `wallets.available_balance`와 `wallets.locked_balance` 분리 | 주문을 넣은 금액/수량을 다른 주문에 중복 사용하지 못하게 하기 위해 사용 가능 잔액과 예약 잔액을 분리한다. |
| `wallet_ledgers` append-only 원장 | 현재 잔액만 저장하면 잔액이 왜 변했는지 추적하기 어렵다. 주문 lock, 취소 release, 체결 정산을 모두 이력으로 남겨 검증 가능하게 한다. |
| `orders.sequence` 사용 | 같은 가격의 주문은 먼저 들어온 주문이 먼저 체결되어야 한다. DB 생성 시간보다 시장별 단조 증가 sequence가 시간 우선순위를 명확하게 만든다. |
| 체결 가격은 maker 주문 가격 | 오더북에 먼저 있던 주문의 가격으로 체결하면 가격 개선과 정산 계산이 명확해진다. |
| `trades`는 저장하고 `fills`는 파생 조회 | trade는 시장에서 발생한 체결 1건이고, fill은 특정 사용자 주문 관점의 조회 결과다. 중복 저장을 줄이기 위해 fill 테이블은 만들지 않는다. |
| DB가 source of truth | 메모리 오더북은 빠른 후보 조회와 호가 조회용 파생 상태다. 장애나 재시작 시 DB의 미체결 주문으로 복구할 수 있어야 한다. |
| 트랜잭션 commit 이후 메모리 오더북 변경 | DB 저장 실패 후 메모리 오더북만 바뀌는 불일치를 막기 위한 기준이다. |
| 동일 시장 명령 순차 처리 | 같은 market의 주문 생성/취소/매칭이 동시에 섞이면 체결 순서와 잔량 정합성이 깨질 수 있다. MVP에서는 성능보다 정합성을 우선한다. |
| `domain_events` 저장 | MVP에서는 내부 이벤트 로그로 사용하고, 이후 outbox/Kafka 확장 시 같은 경계를 사용할 수 있게 한다. |
| `idempotency_requests` 제외 | 1차는 `client_order_id` unique constraint로 주문 중복을 막는다. 취소 같은 command 재시도까지 멱등하게 만들 때 별도 테이블을 추가한다. |
| 입금/출금 패키지 제외 | 입출금은 외부 은행/블록체인 연동, 승인/실패/환불, tx id 중복, 보안 정책이 필요한 별도 도메인이다. MVP의 주문-체결-정산 검증 범위와 분리한다. |

---

## 4. 미팅에서 자주 받을 질문과 답변

### Q. 왜 입금/출금을 안 만들었나?

입출금은 단순히 잔액을 더하고 빼는 기능이 아니라 외부 시스템 연동, 승인 상태, 실패 보상, 중복 transaction 처리, 보안 정책까지 포함하는 별도 도메인이다. 이번 MVP는 거래소 코어인 주문-매칭-체결-정산 정합성을 먼저 증명하는 것이 목표라서 seed balance를 사용한다.

### Q. 왜 지갑과 원장을 분리했나?

`wallets`는 빠른 조회를 위한 현재 잔액 스냅샷이고, `wallet_ledgers`는 잔액 변화의 근거다. 거래소 도메인에서는 현재 잔액만 맞는 것보다 어떤 주문과 체결 때문에 잔액이 바뀌었는지 추적 가능한 구조가 더 중요하다.

### Q. 왜 메모리 오더북을 쓰면서 DB를 source of truth로 두나?

오더북은 매칭 후보 조회와 호가 조회를 빠르게 하기 위한 파생 모델이다. 하지만 최종 정합성 기준은 주문, 체결, 지갑, 원장이 저장된 DB여야 한다. 그래서 서버 시작 시 DB의 `OPEN`, `PARTIALLY_FILLED` 주문으로 오더북을 다시 만들 수 있게 설계한다.

### Q. 왜 fill 테이블을 따로 만들지 않았나?

하나의 체결은 `trades` 한 건으로 충분히 표현된다. 사용자 fill은 같은 trade를 매수자 관점 또는 매도자 관점으로 해석한 조회 결과다. MVP에서는 별도 fill 테이블을 만들면 중복 데이터와 정합성 관리 비용이 늘어난다.

### Q. 왜 `domain_events`가 있는데 Kafka는 제외했나?

MVP에서는 외부 메시지 발행까지 구현하지 않는다. 대신 주문/체결/정산 이벤트를 DB에 남겨 흐름을 추적하고, 나중에 outbox publisher를 붙일 수 있는 경계를 남긴다.

---

## 5. 반드시 지킬 불변식

```text
wallet.available_balance >= 0
wallet.locked_balance >= 0
order.original_quantity = order.executed_quantity + order.remaining_quantity
trade.quote_amount = DOWN(trade.price * trade.quantity, market.amount_scale)
오더북에는 OPEN, PARTIALLY_FILLED 주문만 존재
FILLED, CANCELED 주문은 오더북에 없음
```

이 불변식이 MVP의 핵심 검증 기준이다. 기능이 동작하는지만 보는 것이 아니라, 주문과 체결이 반복되어도 자산과 주문 상태가 깨지지 않는지를 검증한다.

---

## 6. 외부 참고 자료

이 섹션은 2026-04-30 기준 공식 문서에서 확인한 자료를 정리한 것이다. 외부 거래소의 동작을 그대로 복제하려는 목적이 아니라, CoinFlow MVP의 설계 판단이 실제 거래소 API와 같은 문제 영역을 다룬다는 것을 설명하기 위한 근거로 사용한다.

### 6.1 거래소 공식 레퍼런스

| 거래소 | 참고 자료 | 문서에서 확인할 수 있는 포인트 | CoinFlow에서의 사용 |
|---|---|---|---|
| Binance | [Spot API Enums](https://developers.binance.com/docs/binance-spot-api-docs/enums) | 주문 상태 `NEW`, `PARTIALLY_FILLED`, `FILLED`, `CANCELED`, TIF `GTC`, `IOC`, `FOK`, STP 모드 | CoinFlow의 `OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELED` 상태와 MVP의 `GTC` 지정가 주문 범위 설명 |
| Binance | [Spot Trading Endpoints](https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints) | 주문 생성, 취소, `newClientOrderId`, `selfTradePreventionMode`, 주문 응답의 `fills` | `client_order_id` unique 정책, 주문 생성/취소 API, fill을 trade 기반 조회로 다루는 근거 |
| Upbit | [Create Order](https://global-docs.upbit.com/reference/new-order) | `bid`/`ask`, `limit`, `price`, `market`, `identifier`, `time_in_force`, SMP, 주문 생성 시 자산 lock | BUY는 quote asset lock, SELL은 base asset lock으로 설계한 직접 근거 |
| Upbit | [Get Account Balance](https://docs.upbit.com/kr/reference/get-balance) | 계좌 잔고에서 `balance`와 `locked`를 분리 | `wallets.available_balance`, `wallets.locked_balance` 분리 근거 |
| Upbit | [Closed Order 조회](https://docs.upbit.com/kr/v1.5.9/reference/%EC%A2%85%EB%A3%8C-%EC%A3%BC%EB%AC%B8-%EC%A1%B0%ED%9A%8C) | 종료 주문 상태 `done`, `cancel`, `executed_volume`, `remaining_volume`, `locked`, `trades_count` | 주문 상태, 체결 수량, 잔량, lock 해제 조건 설명 |
| Bithumb | [주문 요청](https://apidocs.bithumb.com/reference/%EC%A3%BC%EB%AC%B8-%EC%9A%94%EC%B2%AD) | `bid`/`ask`, `limit`, `price`, `market`, `client_order_id`, JWT 인증 | REST 주문 생성 계약, 클라이언트 주문 ID, JWT 인증 방식 참고 |
| Bithumb | [주문 리스트 조회](https://apidocs.bithumb.com/reference/%EC%A3%BC%EB%AC%B8-%EB%A6%AC%EC%8A%A4%ED%8A%B8-%EC%A1%B0%ED%9A%8C) | 주문 상태 `wait`, `watch`, `done`, `cancel`, `client_order_ids`, 오래된 주문순/최신 주문순 정렬 | 미체결/완료 주문 조회 API, 주문 상태 전이, 정렬 기준 설명 |
| Bithumb | [보유자산 조회](https://apidocs.bithumb.com/v1.2.0/reference/%EB%B3%B4%EC%9C%A0%EC%9E%90%EC%82%B0-%EC%A1%B0%ED%9A%8C) | `available_*`, `in_use_*`, `total_*` 잔고 구분 | available/locked/current snapshot 지갑 모델 설명 |
| Bithumb | [입금 리스트 조회](https://apidocs.bithumb.com/reference/%EC%9E%85%EA%B8%88-%EB%A6%AC%EC%8A%A4%ED%8A%B8-%EC%A1%B0%ED%9A%8C), [출금 리스트 조회](https://apidocs.bithumb.com/reference/%EC%B6%9C%EA%B8%88-%EB%A6%AC%EC%8A%A4%ED%8A%B8-%EC%A1%B0%ED%9A%8C) | 입금/출금은 별도 상태, TXID, 심사/처리/취소 상태를 가진다 | 입금/출금을 MVP에서 제외한 이유 설명 |
| Coinbase | [Exchange Matching Engine](https://docs.cdp.coinbase.com/exchange/concepts/matching-engine) | continuous order book, price-time priority, self-trade prevention, order lifecycle | `orders.sequence`, 가격-시간 우선 매칭, MVP 자기 체결 거절 정책의 핵심 근거 |
| Coinbase | [Get All Orders](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/orders/get-all-orders) | open/pending/done 등 주문 상태, `filled_size`, `executed_value`, `settled`, `client_oid`, `time_in_force` | 주문 조회 응답과 상태/체결/정산 필드 설계 참고 |
| Coinbase | [Get All Fills](https://docs.cdp.coinbase.com/api-reference/exchange-api/rest-api/orders/get-all-fills) | fill은 특정 주문의 partial/complete match, maker/taker liquidity, settlement, fee | `trades`를 저장하고 사용자별 fill은 조회로 파생하는 설계 근거 |
| Coinbase | [Derivatives Order Matching](https://help.coinbase.com/derivatives/features/order-matching) | 같은 가격에서는 FIFO 기준으로 먼저 들어온 주문이 먼저 체결됨 | 같은 가격에서 `orders.sequence`를 사용하는 설명 근거 |
| OKX | [Order Book Trading API](https://www.okx.com/docs-v5/en/) | `POST /api/v5/trade/order`, `clOrdId`, `ordType`, `state`, `stpMode`, 충분한 잔고 필요 | 주문 생성, 주문 상태, 클라이언트 주문 ID, 자기 체결 방지 정책 참고 |
| Bybit | [Place Order](https://bybit-exchange.github.io/docs/v5/order/create-order), [Enums](https://bybit-exchange.github.io/docs/v5/enum) | `orderType`, `timeInForce`, `orderLinkId`, `orderStatus`, `PostOnly`, SMP 관련 cancel reason | 주문 상태 enum, TIF 확장, client order id 역할 참고 |
| Kraken | [WebSocket v2 Add Order](https://docs.kraken.com/api/docs/websocket-v2/add_order/) | `limit`, `market`, TIF `gtc`, `gtd`, `ioc`, `post_only`, `stp_type` | MVP에서 `GTC`만 먼저 구현하고 IOC/FOK/PostOnly/STP를 후순위로 둔 근거 |
| KuCoin | [Add Order Sync](https://www.kucoin.com/docs-new/rest/spot-trading/orders/add-order-sync) | `clientOid`, `limit`, `market`, `price`, `size`, `timeInForce`, `status`, `remainSize` | `client_order_id`, 주문 잔량, 주문 상태 응답 설계 참고 |
| Gemini | [Orders REST API](https://docs.gemini.com/rest/orders), [Client Order ID](https://docs.gemini.com/client-order-id) | `client_order_id`, `is_live`, `is_cancelled`, `executed_amount`, 주문 상태 조회 | 주문 조회 모델과 클라이언트 주문 ID 정책 참고 |
| Bitstamp | [API Documentation](https://www.bitstamp.net/api/) | buy/sell order, order status, `client_order_id`, `amount_remaining`, insufficient balance error | 주문 상태 조회, 잔량, 중복 주문/잔고 부족 에러 정책 참고 |
| Bitget | [Spot Place Order](https://www.bitget.com/api-doc/spot/trade/Place-Order), [Get Order Details](https://www.bitget.com/api-doc/uta/trade/Get-Order-Details) | `clientOid`, `force`/`timeInForce`, `stpMode`, `orderStatus`, 누적 체결 수량/금액 | TIF, STP, 체결 누적 필드의 후순위 확장 근거 |
| Gate.io | [API v4 Spot Orders](https://www.gate.com/docs/developers/apiv4/en) | `text` client field, `time_in_force`, `open`/`closed`/`cancelled`, `left`, `filled_total`, STP | 주문 상태, 잔량, 체결 누적 금액, client id 설계 참고 |
| Bitfinex | [New Order](https://docs.bitfinex.com/v1/reference/rest-auth-new-order), [Order Status](https://docs.bitfinex.com/v1/reference/rest-auth-order-status) | `limit`, `market`, `executed_amount`, `remaining_amount`, `is_live`, `is_cancelled` | 주문 조회 응답에서 원수량/체결수량/잔량을 분리하는 근거 |

### 6.2 레퍼런스로 방어할 MVP 설계 포인트

| CoinFlow 설계 포인트 | 외부 레퍼런스에서 반복적으로 확인되는 패턴 |
|---|---|
| 지정가 주문을 MVP 우선 범위로 둠 | 대부분의 거래소 API가 `limit`을 기본 주문 타입으로 제공하고, 시장가/IOC/FOK/PostOnly/조건부 주문은 별도 정책을 가진다. |
| BUY는 quote 자산, SELL은 base 자산을 lock | Upbit, Bithumb 등 계좌/주문 문서에서 주문 가능 잔고와 묶인 잔고를 분리한다. |
| 주문 상태를 명시적으로 관리 | Binance, Bithumb, Upbit, Bybit, OKX 등 모두 미체결/부분체결/완료/취소 상태를 별도로 표현한다. |
| 체결과 정산을 주문과 분리 | Coinbase fill/settlement 문서처럼 하나의 주문은 여러 체결을 만들 수 있고, 체결 이후 정산 상태가 별도로 존재할 수 있다. |
| `client_order_id`를 둠 | Binance, Coinbase, Bithumb, KuCoin, Gemini, Bitstamp, Bitget 등에서 사용자 지정 주문 ID를 제공한다. |
| 자기 체결 방지는 MVP에서 단순 거절 | Coinbase, Binance, Upbit, OKX, Kraken 등에서 STP/SMP를 별도 정책으로 다룬다. MVP에서는 복잡한 모드 대신 거절 정책으로 범위를 줄인다. |
| 입금/출금 제외 | Upbit/Bithumb 문서처럼 입출금은 주문과 다른 상태, txid, 네트워크, 심사, 보안 정책을 가진 별도 funding 도메인이다. |

### 6.3 기술/보안/정합성 레퍼런스

| 주제 | 참고 자료 | CoinFlow에서의 사용 |
|---|---|---|
| JWT 표준 | [RFC 7519 JSON Web Token](https://www.rfc-editor.org/rfc/rfc7519) | JWT access token의 subject를 현재 사용자 ID로 사용하는 인증 모델 |
| Spring Security JWT 처리 | [Spring Security OAuth2 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) | Spring Security에서 Bearer JWT를 검증하고 principal로 사용하는 구현 참고 |
| 비밀번호 저장 | [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) | password plain text 저장 금지, adaptive hash 사용 근거 |
| DB row lock | [MySQL InnoDB Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html) | wallet row lock, order sequence 발급, maker order 상태 재검증 설계 근거 |
| Spring Data JPA lock | [Spring Data JPA Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html) | repository에서 pessimistic lock을 적용하는 구현 참고 |
| API 멱등성 | [Stripe Idempotent Requests](https://docs.stripe.com/api/idempotent_requests) | 1차는 `client_order_id`로 주문 중복을 막고, command 단위 멱등성은 후순위 확장으로 참고 |
| Transactional outbox | [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox) | `domain_events`를 이벤트 로그이자 outbox 후보로 둔 이유 |

---

## 7. domain_events payload 최소 계약

MVP에서는 내부 이벤트 로그이지만, 이후 outbox/Kafka 확장 시 payload 스키마가 변경되면 consumer 호환성이 깨진다. 아래는 이벤트 타입별 최소 payload 예시이며 구현의 직렬화 기준으로 사용한다.

```json
// ORDER_ACCEPTED
{
  "orderId": 1001,
  "userId": 1,
  "marketSymbol": "BTC-KRW",
  "side": "BUY",
  "type": "LIMIT",
  "price": "100000",
  "quantity": "0.5",
  "sequence": 1
}

// ORDER_PARTIALLY_FILLED
{
  "orderId": 1001,
  "userId": 1,
  "marketSymbol": "BTC-KRW",
  "executedQuantity": "0.2",
  "remainingQuantity": "0.3",
  "tradeId": 501
}

// ORDER_FILLED
{
  "orderId": 1001,
  "userId": 1,
  "marketSymbol": "BTC-KRW",
  "executedQuantity": "0.5",
  "tradeId": 502
}

// ORDER_CANCELED
{
  "orderId": 1001,
  "userId": 1,
  "marketSymbol": "BTC-KRW",
  "remainingQuantity": "0.3",
  "releasedAsset": "KRW",
  "releasedAmount": "30000"
}

// TRADE_CREATED
{
  "tradeId": 501,
  "marketSymbol": "BTC-KRW",
  "buyOrderId": 1001,
  "sellOrderId": 900,
  "makerOrderId": 900,
  "takerOrderId": 1001,
  "price": "98000",
  "quantity": "0.2",
  "quoteAmount": "19600"
}

// SETTLEMENT_COMPLETED
{
  "tradeId": 501,
  "marketSymbol": "BTC-KRW",
  "buyUserId": 1,
  "sellUserId": 2,
  "buyerBaseCredit": "0.2",
  "buyerQuoteRefund": "400",
  "sellerQuoteCredit": "19600"
}
```

## 8. 후순위 확장 방향

| 확장 | MVP 이후 추가 이유 |
|---|---|
| 입금/출금 | 외부 transaction, 승인/실패/환불, 보안 정책이 필요한 별도 funding 도메인 |
| 수수료 | maker/taker fee, 할인 정책, 정산 금액 계산이 추가됨 |
| 시장가 주문 | 금액 기반 주문, 슬리피지, 잔량, 체결 실패 정책이 필요함 |
| WebSocket | 주문/체결 정합성 검증 이후 실시간 전파 계층으로 추가 |
| Kafka/outbox publisher | `domain_events` 기반으로 외부 이벤트 발행 확장 |
| replay/recovery | append-only ledger와 event log가 쌓인 뒤 별도 검증/복구 기능으로 추가 |
