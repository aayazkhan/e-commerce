CREATE TABLE orders (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    checkout_id VARCHAR(64) NOT NULL UNIQUE,
    reservation_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    subtotal_minor BIGINT NOT NULL CHECK (subtotal_minor >= 0),
    item_discount_minor BIGINT NOT NULL CHECK (item_discount_minor >= 0),
    promotion_discount_minor BIGINT NOT NULL CHECK (promotion_discount_minor >= 0),
    shipping_minor BIGINT NOT NULL CHECK (shipping_minor >= 0),
    tax_minor BIGINT NOT NULL CHECK (tax_minor >= 0),
    total_minor BIGINT NOT NULL CHECK (total_minor >= 0),
    currency CHAR(3) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(user_id, idempotency_key),
    UNIQUE(user_id, checkout_id)
);
CREATE INDEX orders_user_created_idx ON orders(user_id, created_at DESC, id DESC);
CREATE INDEX orders_status_idx ON orders(status, created_at DESC);
CREATE TABLE order_items (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64) NOT NULL,
    product_name VARCHAR(300) NOT NULL,
    sku VARCHAR(160),
    seller_id VARCHAR(64),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    tax_minor BIGINT NOT NULL CHECK (tax_minor >= 0),
    discount_minor BIGINT NOT NULL CHECK (discount_minor >= 0),
    line_total_minor BIGINT NOT NULL CHECK (line_total_minor >= 0),
    currency CHAR(3) NOT NULL,
    price_version VARCHAR(128) NOT NULL DEFAULT '',
    attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX order_items_order_idx ON order_items(order_id);
CREATE TABLE order_addresses (
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    address_type VARCHAR(16) NOT NULL CHECK (address_type IN ('SHIPPING','BILLING')),
    address_id VARCHAR(64) NOT NULL,
    recipient_name VARCHAR(200) NOT NULL,
    phone VARCHAR(40) NOT NULL,
    line1 VARCHAR(300) NOT NULL,
    line2 VARCHAR(300),
    city VARCHAR(120) NOT NULL,
    state VARCHAR(120) NOT NULL,
    postal_code VARCHAR(32) NOT NULL,
    country CHAR(2) NOT NULL,
    PRIMARY KEY(order_id, address_type)
);
CREATE TABLE order_price_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    variant_id VARCHAR(64) NOT NULL,
    unit_price_minor BIGINT NOT NULL,
    tax_minor BIGINT NOT NULL,
    discount_minor BIGINT NOT NULL,
    price_version VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL
);
CREATE TABLE order_promotion_snapshots (
    order_id VARCHAR(64) PRIMARY KEY REFERENCES orders(id) ON DELETE CASCADE,
    promotion_id VARCHAR(64),
    coupon_code VARCHAR(64),
    discount_minor BIGINT NOT NULL CHECK (discount_minor >= 0),
    allocation_json JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE order_status_history (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX order_status_history_order_idx ON order_status_history(order_id, created_at DESC);
CREATE TABLE order_cancellations (id VARCHAR(64) PRIMARY KEY, order_id VARCHAR(64) NOT NULL UNIQUE REFERENCES orders(id), reason VARCHAR(500) NOT NULL, requested_by VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL);
CREATE TABLE order_returns (id VARCHAR(64) PRIMARY KEY, order_id VARCHAR(64) NOT NULL REFERENCES orders(id), status VARCHAR(24) NOT NULL, reason VARCHAR(500) NOT NULL, requested_by VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
CREATE INDEX order_returns_order_idx ON order_returns(order_id, created_at DESC);
CREATE TABLE order_return_items (return_id VARCHAR(64) NOT NULL REFERENCES order_returns(id) ON DELETE CASCADE, variant_id VARCHAR(64) NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), PRIMARY KEY(return_id, variant_id));
CREATE TABLE order_inbox_events (event_id VARCHAR(128) PRIMARY KEY, event_type VARCHAR(100) NOT NULL, processed_at TIMESTAMPTZ NOT NULL);
CREATE TABLE order_outbox_events (id VARCHAR(64) PRIMARY KEY, aggregate_id VARCHAR(64) NOT NULL, event_type VARCHAR(100) NOT NULL, schema_version INTEGER NOT NULL DEFAULT 1, occurred_at TIMESTAMPTZ NOT NULL, correlation_id VARCHAR(128) NOT NULL, payload_json JSONB NOT NULL, published_at TIMESTAMPTZ);
CREATE INDEX order_outbox_unpublished_idx ON order_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
