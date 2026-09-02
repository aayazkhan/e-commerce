CREATE TABLE search_inbox_events (
    event_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE search_reindex_jobs (
    id VARCHAR(64) PRIMARY KEY,
    target_index VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    processed_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);
CREATE INDEX search_reindex_jobs_started_idx ON search_reindex_jobs(started_at DESC);
