CREATE TABLE carts (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64),
    guest_id_hash CHAR(64),
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ABANDONED','CHECKED_OUT')),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CHECK ((user_id IS NOT NULL AND guest_id_hash IS NULL) OR (user_id IS NULL AND guest_id_hash IS NOT NULL))
);
CREATE UNIQUE INDEX carts_user_active_idx ON carts(user_id) WHERE user_id IS NOT NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX carts_guest_active_idx ON carts(guest_id_hash) WHERE guest_id_hash IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX carts_updated_idx ON carts(updated_at) WHERE status = 'ACTIVE';

CREATE TABLE cart_items (
    id VARCHAR(64) PRIMARY KEY,
    cart_id VARCHAR(64) NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0 AND quantity <= 99),
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    currency CHAR(3) NOT NULL,
    price_version VARCHAR(64) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(cart_id, variant_id)
);
CREATE INDEX cart_items_cart_idx ON cart_items(cart_id, updated_at);

CREATE TABLE cart_idempotency (
    cart_id VARCHAR(64) NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(cart_id, idempotency_key)
);
CREATE TABLE cart_outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX cart_outbox_unpublished_idx ON cart_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
CREATE TABLE cart_inbox_events (event_id VARCHAR(128) PRIMARY KEY, event_type VARCHAR(100) NOT NULL, processed_at TIMESTAMPTZ NOT NULL);
