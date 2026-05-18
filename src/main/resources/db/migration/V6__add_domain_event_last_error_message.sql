ALTER TABLE domain_events
    ADD COLUMN last_error_message VARCHAR(500) NULL AFTER publish_attempts;
