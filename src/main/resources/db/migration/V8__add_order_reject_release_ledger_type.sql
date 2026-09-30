ALTER TABLE wallet_ledgers
    DROP CHECK chk_wallet_ledgers_type;

ALTER TABLE wallet_ledgers
    ADD CONSTRAINT chk_wallet_ledgers_type CHECK (type IN (
        'SEED_DEPOSIT',
        'ORDER_LOCK',
        'ORDER_CANCEL_RELEASE',
        'ORDER_REJECT_RELEASE',
        'TRADE_BUY_QUOTE_SETTLE',
        'TRADE_BUY_BASE_CREDIT',
        'TRADE_SELL_BASE_SETTLE',
        'TRADE_SELL_QUOTE_CREDIT'
    ));
