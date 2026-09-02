CREATE TABLE wishlists (
    user_id VARCHAR(64) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE wishlist_items (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES wishlists(user_id) ON DELETE CASCADE,
    product_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(user_id, variant_id)
);
CREATE INDEX wishlist_items_cursor_idx ON wishlist_items(user_id, created_at DESC, id DESC);
CREATE TABLE wishlist_outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX wishlist_outbox_unpublished_idx ON wishlist_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
CREATE TABLE wishlist_inbox_events (event_id VARCHAR(128) PRIMARY KEY, event_type VARCHAR(100) NOT NULL, processed_at TIMESTAMPTZ NOT NULL);
