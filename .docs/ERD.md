# CoinFlow MVP ERD

이 문서는 CoinFlow MVP 구현 기준의 MySQL DDL이다.

목표는 회원가입/로그인, 주문 생성, 취소, 매칭, 체결, 정산, 조회 흐름을 단일 인스턴스 환경에서 정합성 있게 구현하는 것이다. 입출금, 수수료, 일반적인 dust 정책, Kafka, Redis, recovery/replay/redrive, 인증 고도화는 MVP 범위에서 제외한다. 단, zero-quote 체결 방지와 dust maker 잔량 자동 취소는 DB 제약과 정합성 보호를 위한 Phase 1 이후 보강으로 포함한다.

## 설계 기준

- 외부 API는 `market_symbol` 예: `BTC-KRW`를 사용하고, 내부 FK는 `market_id`를 사용한다.
- `assets`는 자산 코드, 표시명, 상태의 기준이다. 주문 검증 단위와 최소 주문 정책은 `markets`에서 관리한다.
- 동일 시장 내 시간 우선순위는 `orders.sequence`로 보장한다.
- `wallets`는 현재 잔액 스냅샷이고, `wallet_ledgers`는 append-only 변동 이력이다.
- 매수 주문은 quote asset을 잠그고, 매도 주문은 base asset을 잠근다.
- 체결 가격은 maker 주문 가격을 따른다.
- `trades.quote_amount`와 `orders.executed_quote_amount`는 체결 당시 확정된 quote 금액을 저장한다.
- `quote_amount`는 `price * quantity`를 `markets.amount_scale` 기준으로 DOWN rounding하여 확정한다.
- BUY 주문의 `locked_amount`는 `price * remaining_quantity`를 `markets.amount_scale` 기준으로 CEILING rounding하여 계산한다.
- 부분 체결 시 `locked_amount`는 단순 차감하지 않고 `price * new_remaining_quantity`로 재계산(CEILING)한다. `released_amount = old_locked - new_locked`, `buyer_refund = released_amount - trade_quote_amount`.
- `domain_events`는 MVP에서는 이벤트 로그 겸 outbox 후보 테이블로 사용한다.

## 상태값 정책

### Order status

```text
OPEN
PARTIALLY_FILLED
FILLED
CANCELED
```

### Order type

```text
LIMIT
```

### Time in force

```text
GTC
```

### Ledger type

```text
SEED_DEPOSIT
ORDER_LOCK
ORDER_CANCEL_RELEASE
TRADE_BUY_QUOTE_SETTLE
TRADE_BUY_BASE_CREDIT
TRADE_SELL_BASE_SETTLE
TRADE_SELL_QUOTE_CREDIT
```

## MySQL DDL

ERD Cloud에 import할 때는 Markdown 코드블록이 아니라 [ERDCloud.sql](./ERDCloud.sql)을 사용한다.

```sql
-- ============================================================
-- CoinFlow MVP ERD - MySQL DDL
-- ERD Cloud import friendly
-- ============================================================

-- 1. users
-- MVP에서는 email/password 기반 회원가입과 JWT access token 로그인을 지원한다.
-- refresh token, OAuth, role/permission 기반 권한 모델은 제외한다.
CREATE TABLE users (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    nickname       VARCHAR(100) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- 2. assets
-- 자산별 표시명과 상태 정책.
-- 예: BTC, KRW, USD
CREATE TABLE assets (
    code             VARCHAR(20)     NOT NULL,
    name             VARCHAR(100)    NOT NULL,
    display_name     VARCHAR(100)    NOT NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (code),
    CONSTRAINT chk_assets_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);

-- 3. markets
-- price/quantity 검증 정책은 market에 둔다.
-- amount_scale은 quote_amount 반올림 정책에 사용한다.
-- 예: BTC-KRW, base_asset=BTC, quote_asset=KRW
CREATE TABLE markets (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    symbol              VARCHAR(50)     NOT NULL,
    display_name        VARCHAR(100)    NOT NULL,
    base_asset          VARCHAR(20)     NOT NULL,
    quote_asset         VARCHAR(20)     NOT NULL,

    amount_scale        INT             NOT NULL,

    tick_size           DECIMAL(38, 18) NOT NULL,
    step_size           DECIMAL(38, 18) NOT NULL,
    min_order_quantity  DECIMAL(38, 18) NOT NULL,
    min_order_amount    DECIMAL(38, 18) NOT NULL,

    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    cancel_only         BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uq_markets_symbol (symbol),
    CONSTRAINT fk_markets_base_asset FOREIGN KEY (base_asset) REFERENCES assets (code),
    CONSTRAINT fk_markets_quote_asset FOREIGN KEY (quote_asset) REFERENCES assets (code),
    CONSTRAINT chk_markets_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    CONSTRAINT chk_markets_amount_scale CHECK (amount_scale >= 0),
    CONSTRAINT chk_markets_units CHECK (
        tick_size > 0
        AND step_size > 0
        AND min_order_quantity > 0
        AND min_order_amount > 0
    )
);

-- 4. wallets
-- 현재 잔액 스냅샷.
-- 모든 변경은 wallet_ledgers에 append-only로 남긴다.
CREATE TABLE wallets (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    asset               VARCHAR(20)     NOT NULL,
    available_balance   DECIMAL(38, 18) NOT NULL DEFAULT 0,
    locked_balance      DECIMAL(38, 18) NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uq_wallets_user_asset (user_id, asset),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_wallets_asset FOREIGN KEY (asset) REFERENCES assets (code),
    CONSTRAINT chk_wallets_balance CHECK (
        available_balance >= 0
        AND locked_balance >= 0
    )
);

-- 5. order_sequences
-- 동일 market 내 price-time priority의 time priority를 보장하기 위한 단조 증가 sequence.
CREATE TABLE order_sequences (
    market_id      BIGINT NOT NULL,
    last_sequence  BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (market_id),
    CONSTRAINT fk_order_sequences_market FOREIGN KEY (market_id) REFERENCES markets (id)
);

-- 6. orders
-- 지정가 GTC 주문만 MVP에서 지원한다.
CREATE TABLE orders (
    id                     BIGINT          NOT NULL AUTO_INCREMENT,
    client_order_id         VARCHAR(64)     NULL,

    user_id                BIGINT          NOT NULL,
    market_id              BIGINT          NOT NULL,
    market_symbol          VARCHAR(50)     NOT NULL,

    side                   VARCHAR(10)     NOT NULL,
    type                   VARCHAR(10)     NOT NULL DEFAULT 'LIMIT',
    time_in_force          VARCHAR(10)     NOT NULL DEFAULT 'GTC',

    price                  DECIMAL(38, 18) NOT NULL,
    original_quantity      DECIMAL(38, 18) NOT NULL,
    remaining_quantity     DECIMAL(38, 18) NOT NULL,
    executed_quantity      DECIMAL(38, 18) NOT NULL DEFAULT 0,
    executed_quote_amount  DECIMAL(38, 18) NOT NULL DEFAULT 0,

    locked_asset           VARCHAR(20)     NOT NULL,
    locked_amount          DECIMAL(38, 18) NOT NULL DEFAULT 0,

    status                 VARCHAR(30)     NOT NULL DEFAULT 'OPEN',
    sequence               BIGINT          NOT NULL,

    created_at             DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    closed_at              DATETIME(6)     NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_user_client_order (user_id, client_order_id),
    UNIQUE KEY uq_orders_market_sequence (market_id, sequence),

    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT fk_orders_locked_asset FOREIGN KEY (locked_asset) REFERENCES assets (code),

    CONSTRAINT chk_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_orders_type CHECK (type IN ('LIMIT')),
    CONSTRAINT chk_orders_time_in_force CHECK (time_in_force IN ('GTC')),
    CONSTRAINT chk_orders_status CHECK (status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED')),
    CONSTRAINT chk_orders_amounts CHECK (
        price > 0
        AND original_quantity > 0
        AND remaining_quantity >= 0
        AND executed_quantity >= 0
        AND executed_quote_amount >= 0
        AND locked_amount >= 0
        AND original_quantity = remaining_quantity + executed_quantity
    ),

    -- 사용자 주문 목록 조회
    INDEX idx_orders_user_created (user_id, created_at),
    INDEX idx_orders_user_market_status_created (user_id, market_id, status, created_at),

    -- 오더북 초기화 및 매칭 후보 조회.
    -- BUY side는 price DESC, SELL side는 price ASC 기준으로 사용한다.
    INDEX idx_orders_book_buy (market_id, status, side, price DESC, sequence ASC),
    INDEX idx_orders_book_sell (market_id, status, side, price ASC, sequence ASC)
);

-- 7. trades
-- 체결 가격은 maker order price이다.
-- quote_amount는 price * quantity를 체결 시점의 scale/rounding 기준으로 확정한 값이다.
CREATE TABLE trades (
    id                BIGINT          NOT NULL AUTO_INCREMENT,

    market_id         BIGINT          NOT NULL,
    market_symbol     VARCHAR(50)     NOT NULL,

    buy_order_id      BIGINT          NOT NULL,
    sell_order_id     BIGINT          NOT NULL,
    maker_order_id    BIGINT          NOT NULL,
    taker_order_id    BIGINT          NOT NULL,

    buy_user_id       BIGINT          NOT NULL,
    sell_user_id      BIGINT          NOT NULL,

    price             DECIMAL(38, 18) NOT NULL,
    quantity          DECIMAL(38, 18) NOT NULL,
    quote_amount      DECIMAL(38, 18) NOT NULL,

    traded_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_trades_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT fk_trades_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_maker_order FOREIGN KEY (maker_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_taker_order FOREIGN KEY (taker_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_buy_user FOREIGN KEY (buy_user_id) REFERENCES users (id),
    CONSTRAINT fk_trades_sell_user FOREIGN KEY (sell_user_id) REFERENCES users (id),
    CONSTRAINT chk_trades_amounts CHECK (
        price > 0
        AND quantity > 0
        AND quote_amount > 0
    ),

    INDEX idx_trades_market_traded (market_id, traded_at),
    INDEX idx_trades_buy_order (buy_order_id),
    INDEX idx_trades_sell_order (sell_order_id),
    INDEX idx_trades_maker_order (maker_order_id),
    INDEX idx_trades_taker_order (taker_order_id),

    -- 사용자 fill 조회: GET /api/v1/fills
    INDEX idx_trades_buy_user_traded (buy_user_id, traded_at),
    INDEX idx_trades_sell_user_traded (sell_user_id, traded_at)
);

-- 8. wallet_ledgers
-- append-only 원장.
-- available/locked의 변화량과 변화 후 스냅샷을 같이 저장한다.
-- reference_type/reference_id는 제거. 현재 API 응답은 order_id, trade_id만 노출한다.
CREATE TABLE wallet_ledgers (
    id                       BIGINT          NOT NULL AUTO_INCREMENT,

    user_id                  BIGINT          NOT NULL,
    wallet_id                BIGINT          NOT NULL,
    asset                    VARCHAR(20)     NOT NULL,

    type                     VARCHAR(40)     NOT NULL,
    delta_available          DECIMAL(38, 18) NOT NULL,
    delta_locked             DECIMAL(38, 18) NOT NULL,
    available_balance_after  DECIMAL(38, 18) NOT NULL,
    locked_balance_after     DECIMAL(38, 18) NOT NULL,

    order_id                 BIGINT          NULL,
    trade_id                 BIGINT          NULL,

    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_wallet_ledgers_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_wallet_ledgers_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_wallet_ledgers_asset FOREIGN KEY (asset) REFERENCES assets (code),
    CONSTRAINT fk_wallet_ledgers_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_wallet_ledgers_trade FOREIGN KEY (trade_id) REFERENCES trades (id),
    CONSTRAINT chk_wallet_ledgers_type CHECK (type IN (
        'SEED_DEPOSIT',
        'ORDER_LOCK',
        'ORDER_CANCEL_RELEASE',
        'TRADE_BUY_QUOTE_SETTLE',
        'TRADE_BUY_BASE_CREDIT',
        'TRADE_SELL_BASE_SETTLE',
        'TRADE_SELL_QUOTE_CREDIT'
    )),
    CONSTRAINT chk_wallet_ledgers_after_balance CHECK (
        available_balance_after >= 0
        AND locked_balance_after >= 0
    ),

    INDEX idx_wallet_ledgers_user_created (user_id, created_at),
    INDEX idx_wallet_ledgers_user_asset_created (user_id, asset, created_at),
    INDEX idx_wallet_ledgers_wallet_created (wallet_id, created_at),
    INDEX idx_wallet_ledgers_order (order_id),
    INDEX idx_wallet_ledgers_trade (trade_id)
);

-- 9. domain_events
-- 주요 도메인 이벤트 로그이자 Kafka 발행을 위한 outbox로 사용한다.
CREATE TABLE domain_events (
    id               BIGINT       NOT NULL AUTO_INCREMENT,

    event_type       VARCHAR(60)  NOT NULL,
    aggregate_type   VARCHAR(50)  NOT NULL,
    aggregate_id     BIGINT       NOT NULL,

    market_id        BIGINT       NULL,
    market_symbol    VARCHAR(50)  NULL,

    payload          TEXT         NOT NULL,

    published        BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at     DATETIME(6)  NULL,
    publish_attempts INT          NOT NULL DEFAULT 0,
    last_error_message VARCHAR(500) NULL,

    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_domain_events_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT chk_domain_events_type CHECK (event_type IN (
        'ORDER_ACCEPTED',
        'ORDER_PARTIALLY_FILLED',
        'ORDER_FILLED',
        'ORDER_CANCELED',
        'TRADE_CREATED',
        'SETTLEMENT_COMPLETED'
    )),
    CONSTRAINT chk_domain_events_publish_attempts CHECK (publish_attempts >= 0),

    INDEX idx_domain_events_aggregate (aggregate_type, aggregate_id),
    INDEX idx_domain_events_market_created (market_id, created_at),
    INDEX idx_domain_events_type_created (event_type, created_at),
    INDEX idx_domain_events_published_created (published, created_at)
);

```

## 테이블별 역할 요약

| 테이블 | 역할 |
|---|---|
| `users` | 회원가입/로그인 사용자와 FK 기준 |
| `assets` | 자산 표시명과 상태 정책 |
| `markets` | 마켓 정보와 주문 검증 정책 |
| `wallets` | 사용자별 자산 현재 잔액 |
| `order_sequences` | 시장별 주문 우선순위 sequence |
| `orders` | 주문 원장과 현재 주문 상태 |
| `trades` | 체결 기록 |
| `wallet_ledgers` | 지갑 변동 append-only 원장 |
| `domain_events` | 주요 도메인 이벤트 로그 / outbox 후보 |

## 구현 시 주의사항

- `orders.sequence`는 `order_sequences`를 `SELECT ... FOR UPDATE`로 잠근 뒤 증가시켜 발급한다.
- `wallets` 변경은 항상 `SELECT ... FOR UPDATE`로 wallet row를 잠근 뒤 수행한다.
- 여러 wallet을 동시에 잠글 때는 `(user_id, asset)` 오름차순으로 잠근다.
- 주문, 체결, 정산, 원장, 이벤트 로그는 하나의 트랜잭션 안에서 기록한다.
- 메모리 오더북은 트랜잭션 commit 이후에만 변경한다.
- 서버 시작 시 `OPEN`, `PARTIALLY_FILLED` 주문만 메모리 오더북에 적재한다.
- `DECIMAL(38, 18)`은 MVP에서 precision 문제를 줄이기 위한 기본값이다. 실제 운영 자산별 scale 정책은 별도 문서로 분리한다.
