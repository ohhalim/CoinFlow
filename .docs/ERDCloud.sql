-- ============================================================
-- CoinFlow MVP ERD - ERD Cloud Import DDL
-- ============================================================
-- Import note:
-- This file is intentionally simpler than the implementation DDL.
-- It keeps tables, columns, primary keys, unique keys, foreign keys, and indexes.
-- CHECK constraints, engine options, and index direction (DESC/ASC) are omitted
-- for ERD Cloud compatibility. Index columns are listed without direction markers.

CREATE TABLE users (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    nickname       VARCHAR(100) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email)
);

CREATE TABLE assets (
    code             VARCHAR(20)     NOT NULL,
    name             VARCHAR(100)    NOT NULL,
    display_name     VARCHAR(100)    NOT NULL,
    status           VARCHAR(20)     NOT NULL,
    created_at       DATETIME        NOT NULL,
    updated_at       DATETIME        NOT NULL,

    PRIMARY KEY (code)
);

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

    status              VARCHAR(20)     NOT NULL,
    cancel_only         TINYINT         NOT NULL,
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_markets_symbol (symbol),
    CONSTRAINT fk_markets_base_asset FOREIGN KEY (base_asset) REFERENCES assets (code),
    CONSTRAINT fk_markets_quote_asset FOREIGN KEY (quote_asset) REFERENCES assets (code)
);

CREATE TABLE wallets (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    asset               VARCHAR(20)     NOT NULL,
    available_balance   DECIMAL(38, 18) NOT NULL,
    locked_balance      DECIMAL(38, 18) NOT NULL,
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_wallets_user_asset (user_id, asset),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_wallets_asset FOREIGN KEY (asset) REFERENCES assets (code)
);

CREATE TABLE order_sequences (
    market_id      BIGINT NOT NULL,
    last_sequence  BIGINT NOT NULL,

    PRIMARY KEY (market_id),
    CONSTRAINT fk_order_sequences_market FOREIGN KEY (market_id) REFERENCES markets (id)
);

CREATE TABLE orders (
    id                     BIGINT          NOT NULL AUTO_INCREMENT,
    client_order_id         VARCHAR(64)     NULL,

    user_id                BIGINT          NOT NULL,
    market_id              BIGINT          NOT NULL,
    market_symbol          VARCHAR(50)     NOT NULL,

    side                   VARCHAR(10)     NOT NULL,
    type                   VARCHAR(10)     NOT NULL,
    time_in_force          VARCHAR(10)     NOT NULL,

    price                  DECIMAL(38, 18) NOT NULL,
    original_quantity      DECIMAL(38, 18) NOT NULL,
    remaining_quantity     DECIMAL(38, 18) NOT NULL,
    executed_quantity      DECIMAL(38, 18) NOT NULL,
    executed_quote_amount  DECIMAL(38, 18) NOT NULL,

    locked_asset           VARCHAR(20)     NOT NULL,
    locked_amount          DECIMAL(38, 18) NOT NULL,

    status                 VARCHAR(30)     NOT NULL,
    sequence               BIGINT          NOT NULL,

    created_at             DATETIME        NOT NULL,
    updated_at             DATETIME        NOT NULL,
    closed_at              DATETIME        NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_user_client_order (user_id, client_order_id),
    UNIQUE KEY uq_orders_market_sequence (market_id, sequence),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT fk_orders_locked_asset FOREIGN KEY (locked_asset) REFERENCES assets (code),

    INDEX idx_orders_user_created (user_id, created_at),
    INDEX idx_orders_user_market_status_created (user_id, market_id, status, created_at),
    INDEX idx_orders_book_buy (market_id, status, side, price, sequence),
    INDEX idx_orders_book_sell (market_id, status, side, price, sequence)
);

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

    traded_at         DATETIME        NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_trades_market FOREIGN KEY (market_id) REFERENCES markets (id),
    CONSTRAINT fk_trades_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_maker_order FOREIGN KEY (maker_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_taker_order FOREIGN KEY (taker_order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_buy_user FOREIGN KEY (buy_user_id) REFERENCES users (id),
    CONSTRAINT fk_trades_sell_user FOREIGN KEY (sell_user_id) REFERENCES users (id),

    INDEX idx_trades_market_traded (market_id, traded_at),
    INDEX idx_trades_buy_order (buy_order_id),
    INDEX idx_trades_sell_order (sell_order_id),
    INDEX idx_trades_maker_order (maker_order_id),
    INDEX idx_trades_taker_order (taker_order_id),
    INDEX idx_trades_buy_user_traded (buy_user_id, traded_at),
    INDEX idx_trades_sell_user_traded (sell_user_id, traded_at)
);

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

    created_at               DATETIME        NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_wallet_ledgers_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_wallet_ledgers_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_wallet_ledgers_asset FOREIGN KEY (asset) REFERENCES assets (code),
    CONSTRAINT fk_wallet_ledgers_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_wallet_ledgers_trade FOREIGN KEY (trade_id) REFERENCES trades (id),

    INDEX idx_wallet_ledgers_user_created (user_id, created_at),
    INDEX idx_wallet_ledgers_user_asset_created (user_id, asset, created_at),
    INDEX idx_wallet_ledgers_wallet_created (wallet_id, created_at),
    INDEX idx_wallet_ledgers_order (order_id),
    INDEX idx_wallet_ledgers_trade (trade_id)
);

CREATE TABLE domain_events (
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    event_type        VARCHAR(60)  NOT NULL,
    aggregate_type    VARCHAR(50)  NOT NULL,
    aggregate_id      BIGINT       NOT NULL,

    market_id         BIGINT       NULL,
    market_symbol     VARCHAR(50)  NULL,

    payload           TEXT         NOT NULL,

    published         TINYINT      NOT NULL,
    published_at      DATETIME     NULL,
    publish_attempts  INT          NOT NULL,

    created_at        DATETIME     NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_domain_events_market FOREIGN KEY (market_id) REFERENCES markets (id),

    INDEX idx_domain_events_aggregate (aggregate_type, aggregate_id),
    INDEX idx_domain_events_market_created (market_id, created_at),
    INDEX idx_domain_events_type_created (event_type, created_at),
    INDEX idx_domain_events_published_created (published, created_at)
);
