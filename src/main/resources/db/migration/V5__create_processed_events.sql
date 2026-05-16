CREATE TABLE processed_events (
    event_id     BIGINT      NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id, consumer)
);
