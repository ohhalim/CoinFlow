-- 1. orders
CREATE TABLE orders (
    id                    BIGINT          NOT NULL AUTO_INCREMENT,
    client_order_id       VARCHAR(64)     NULL,

    user_id               BIGINT          NOT NULL,
    market_id             BIGINT          NOT NULL,
    market_symbol         VARCHAR(50)     NOT NULL,

    side                  VARCHAR(10)     NOT NULL,
    type                  VARCHAR(10)     NOT NULL DEFAULT 'LIMIT',
    time_in_force         VARCHAR(10)     NOT NULL DEFAULT 'GTC',

    price                 DECIMAL(38, 18) NOT NULL,
    original_quantity     DECIMAL(38, 18) NOT NULL,
    remaining_quantity    DECIMAL(38, 18) NOT NULL,
    executed_quantity     DECIMAL(38, 18) NOT NULL DEFAULT 0,
    executed_quote_amount DECIMAL(38, 18) NOT NULL DEFAULT 0,

    locked_asset          VARCHAR(20)     NOT NULL,
    locked_amount         DECIMAL(38, 18) NOT NULL DEFAULT 0,

    status                VARCHAR(30)     NOT NULL DEFAULT 'OPEN',
    sequence              BIGINT          NOT NULL,

    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    closed_at             DATETIME(6)     NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_user_client_order (user_id, client_order_id),
    UNIQUE KEY uq_orders_market_sequence   (market_id, sequence),

    CONSTRAINT fk_orders_user         FOREIGN KEY (user_id)      REFERENCES users   (id),
    CONSTRAINT fk_orders_market       FOREIGN KEY (market_id)    REFERENCES markets (id),
    CONSTRAINT fk_orders_locked_asset FOREIGN KEY (locked_asset) REFERENCES assets  (code),

    CONSTRAINT chk_orders_side          CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_orders_type          CHECK (type IN ('LIMIT')),
    CONSTRAINT chk_orders_time_in_force CHECK (time_in_force IN ('GTC')),
    CONSTRAINT chk_orders_status        CHECK (status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED')),
    CONSTRAINT chk_orders_amounts CHECK (
        price > 0
        AND original_quantity > 0
        AND remaining_quantity >= 0
        AND executed_quantity >= 0
        AND executed_quote_amount >= 0
        AND locked_amount >= 0
        AND original_quantity = remaining_quantity + executed_quantity
    ),

    INDEX idx_orders_user_created                  (user_id, created_at),
    INDEX idx_orders_user_market_status_created    (user_id, market_id, status, created_at),
    INDEX idx_orders_book_buy  (market_id, status, side, price DESC, sequence ASC),
    INDEX idx_orders_book_sell (market_id, status, side, price ASC,  sequence ASC)
);

-- 2. trades
CREATE TABLE trades (
    id            BIGINT          NOT NULL AUTO_INCREMENT,

    market_id     BIGINT          NOT NULL,
    market_symbol VARCHAR(50)     NOT NULL,

    buy_order_id  BIGINT          NOT NULL,
    sell_order_id BIGINT          NOT NULL,
    maker_order_id BIGINT         NOT NULL,
    taker_order_id BIGINT         NOT NULL,

    buy_user_id   BIGINT          NOT NULL,
    sell_user_id  BIGINT          NOT NULL,

    price         DECIMAL(38, 18) NOT NULL,
    quantity      DECIMAL(38, 18) NOT NULL,
    quote_amount  DECIMAL(38, 18) NOT NULL,

    traded_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_trades_market      FOREIGN KEY (market_id)      REFERENCES markets (id),
    CONSTRAINT fk_trades_buy_order   FOREIGN KEY (buy_order_id)   REFERENCES orders  (id),
    CONSTRAINT fk_trades_sell_order  FOREIGN KEY (sell_order_id)  REFERENCES orders  (id),
    CONSTRAINT fk_trades_maker_order FOREIGN KEY (maker_order_id) REFERENCES orders  (id),
    CONSTRAINT fk_trades_taker_order FOREIGN KEY (taker_order_id) REFERENCES orders  (id),
    CONSTRAINT fk_trades_buy_user    FOREIGN KEY (buy_user_id)    REFERENCES users   (id),
    CONSTRAINT fk_trades_sell_user   FOREIGN KEY (sell_user_id)   REFERENCES users   (id),
    CONSTRAINT chk_trades_amounts CHECK (
        price > 0
        AND quantity > 0
        AND quote_amount > 0
    ),

    INDEX idx_trades_market_traded    (market_id, traded_at),
    INDEX idx_trades_buy_order        (buy_order_id),
    INDEX idx_trades_sell_order       (sell_order_id),
    INDEX idx_trades_maker_order      (maker_order_id),
    INDEX idx_trades_taker_order      (taker_order_id),
    INDEX idx_trades_buy_user_traded  (buy_user_id,  traded_at),
    INDEX idx_trades_sell_user_traded (sell_user_id, traded_at)
);

-- 3. wallet_ledgers
CREATE TABLE wallet_ledgers (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,

    user_id                 BIGINT          NOT NULL,
    wallet_id               BIGINT          NOT NULL,
    asset                   VARCHAR(20)     NOT NULL,

    type                    VARCHAR(40)     NOT NULL,
    delta_available         DECIMAL(38, 18) NOT NULL,
    delta_locked            DECIMAL(38, 18) NOT NULL,
    available_balance_after DECIMAL(38, 18) NOT NULL,
    locked_balance_after    DECIMAL(38, 18) NOT NULL,

    order_id                BIGINT          NULL,
    trade_id                BIGINT          NULL,

    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_wallet_ledgers_user   FOREIGN KEY (user_id)   REFERENCES users   (id),
    CONSTRAINT fk_wallet_ledgers_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_wallet_ledgers_asset  FOREIGN KEY (asset)     REFERENCES assets  (code),
    CONSTRAINT fk_wallet_ledgers_order  FOREIGN KEY (order_id)  REFERENCES orders  (id),
    CONSTRAINT fk_wallet_ledgers_trade  FOREIGN KEY (trade_id)  REFERENCES trades  (id),
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

    INDEX idx_wallet_ledgers_user_created       (user_id, created_at),
    INDEX idx_wallet_ledgers_user_asset_created (user_id, asset, created_at),
    INDEX idx_wallet_ledgers_wallet_created     (wallet_id, created_at),
    INDEX idx_wallet_ledgers_order              (order_id),
    INDEX idx_wallet_ledgers_trade              (trade_id)
);

-- 4. domain_events
CREATE TABLE domain_events (
    id               BIGINT      NOT NULL AUTO_INCREMENT,

    event_type       VARCHAR(60) NOT NULL,
    aggregate_type   VARCHAR(50) NOT NULL,
    aggregate_id     BIGINT      NOT NULL,

    market_id        BIGINT      NULL,
    market_symbol    VARCHAR(50) NULL,

    payload          TEXT        NOT NULL,

    published        BOOLEAN     NOT NULL DEFAULT FALSE,
    published_at     DATETIME(6) NULL,
    publish_attempts INT         NOT NULL DEFAULT 0,

    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

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

    INDEX idx_domain_events_aggregate       (aggregate_type, aggregate_id),
    INDEX idx_domain_events_market_created  (market_id, created_at),
    INDEX idx_domain_events_type_created    (event_type, created_at),
    INDEX idx_domain_events_published_created (published, created_at)
);
