-- 1. assets
CREATE TABLE assets (
    code         VARCHAR(20)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (code),
    CONSTRAINT chk_assets_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);

-- 2. markets
CREATE TABLE markets (
    id                 BIGINT          NOT NULL AUTO_INCREMENT,
    symbol             VARCHAR(50)     NOT NULL,
    display_name       VARCHAR(100)    NOT NULL,
    base_asset         VARCHAR(20)     NOT NULL,
    quote_asset        VARCHAR(20)     NOT NULL,

    amount_scale       INT             NOT NULL,

    tick_size          DECIMAL(38, 18) NOT NULL,
    step_size          DECIMAL(38, 18) NOT NULL,
    min_order_quantity DECIMAL(38, 18) NOT NULL,
    min_order_amount   DECIMAL(38, 18) NOT NULL,

    status             VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    cancel_only        BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uq_markets_symbol (symbol),
    CONSTRAINT fk_markets_base_asset  FOREIGN KEY (base_asset)  REFERENCES assets (code),
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

-- 3. wallets
CREATE TABLE wallets (
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    user_id           BIGINT          NOT NULL,
    asset             VARCHAR(20)     NOT NULL,
    available_balance DECIMAL(38, 18) NOT NULL DEFAULT 0,
    locked_balance    DECIMAL(38, 18) NOT NULL DEFAULT 0,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uq_wallets_user_asset (user_id, asset),
    CONSTRAINT fk_wallets_user  FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_wallets_asset FOREIGN KEY (asset)   REFERENCES assets (code),
    CONSTRAINT chk_wallets_balance CHECK (
        available_balance >= 0
        AND locked_balance >= 0
    )
);

-- 4. order_sequences
CREATE TABLE order_sequences (
    market_id     BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (market_id),
    CONSTRAINT fk_order_sequences_market FOREIGN KEY (market_id) REFERENCES markets (id)
);
