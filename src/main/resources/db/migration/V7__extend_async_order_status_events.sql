ALTER TABLE orders
    DROP CHECK chk_orders_status;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status
        CHECK (status IN ('ACCEPTED', 'OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED'));

ALTER TABLE domain_events
    DROP CHECK chk_domain_events_type;

ALTER TABLE domain_events
    ADD CONSTRAINT chk_domain_events_type CHECK (event_type IN (
        'ORDER_ACCEPTED',
        'ORDER_PARTIALLY_FILLED',
        'ORDER_FILLED',
        'ORDER_REJECTED',
        'ORDER_CANCELED',
        'TRADE_CREATED',
        'SETTLEMENT_COMPLETED'
    ));
