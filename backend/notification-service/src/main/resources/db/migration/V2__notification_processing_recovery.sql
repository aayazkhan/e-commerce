CREATE INDEX notification_processing_recovery_idx
    ON notification_deliveries(status, updated_at)
    WHERE status = 'PROCESSING';
