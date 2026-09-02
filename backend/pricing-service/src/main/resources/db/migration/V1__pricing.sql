CREATE TABLE prices (
    id VARCHAR(64) PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64),
    seller_id VARCHAR(64),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    region VARCHAR(8) NOT NULL,
    customer_segment VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
    base_minor BIGINT NOT NULL CHECK (base_minor >= 0),
    sale_minor BIGINT CHECK (sale_minor IS NULL OR sale_minor >= 0),
    tax_rate_bps INTEGER NOT NULL DEFAULT 0 CHECK (tax_rate_bps BETWEEN 0 AND 10000),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (sale_minor IS NULL OR sale_minor <= base_minor)
);
CREATE INDEX prices_lookup_idx ON prices(product_id, variant_id, currency, region, customer_segment, effective_from DESC);
CREATE INDEX prices_effective_idx ON prices(effective_from, effective_to);

CREATE TABLE price_versions (
    id VARCHAR(64) PRIMARY KEY,
    price_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    snapshot_json JSONB NOT NULL,
    changed_by VARCHAR(64) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    UNIQUE(price_id, version)
);

CREATE TABLE pricing_outbox_events (
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
CREATE INDEX pricing_outbox_unpublished_idx ON pricing_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
