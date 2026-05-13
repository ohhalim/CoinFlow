-- assets
INSERT INTO assets (code, name, display_name, status) VALUES
    ('KRW', 'Korean Won',  '원화',    'ACTIVE'),
    ('BTC', 'Bitcoin',     '비트코인', 'ACTIVE');

-- markets
INSERT INTO markets (symbol, display_name, base_asset, quote_asset, amount_scale, tick_size, step_size, min_order_quantity, min_order_amount, status, cancel_only) VALUES
    ('BTC-KRW', 'BTC/KRW', 'BTC', 'KRW', 0, 1, 0.00000001, 0.0001, 5000, 'ACTIVE', FALSE);

-- order_sequences (market_id=1 은 위에서 INSERT된 BTC-KRW)
INSERT INTO order_sequences (market_id, last_sequence) VALUES
    (1, 0);
