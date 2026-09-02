CREATE TABLE media_assets (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    bucket VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255),
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    checksum_sha256 CHAR(64) NOT NULL,
    width INTEGER,
    height INTEGER,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_UPLOAD','PROCESSING','READY','FAILED','DELETED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX media_assets_owner_idx ON media_assets(owner_id, status, created_at DESC);

CREATE TABLE media_variants (
    id VARCHAR(64) PRIMARY KEY,
    media_id VARCHAR(64) NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    variant_type VARCHAR(24) NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(media_id, variant_type)
);

CREATE TABLE media_processing_jobs (
    id VARCHAR(64) PRIMARY KEY,
    media_id VARCHAR(64) NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE media_outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX media_outbox_unpublished_idx ON media_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
