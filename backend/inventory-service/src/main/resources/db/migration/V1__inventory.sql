CREATE TABLE inventory_warehouses (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    country CHAR(2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_items (
    id VARCHAR(64) PRIMARY KEY,
    warehouse_id VARCHAR(64) NOT NULL REFERENCES inventory_warehouses(id),
    product_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64) NOT NULL,
    on_hand BIGINT NOT NULL CHECK (on_hand >= 0),
    reserved BIGINT NOT NULL CHECK (reserved >= 0),
    available BIGINT NOT NULL CHECK (available >= 0),
    low_stock_threshold BIGINT NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
    low_stock_notified BOOLEAN NOT NULL DEFAULT FALSE,
    out_of_stock_notified BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(warehouse_id, variant_id),
    CHECK (available = on_hand - reserved),
    CHECK (on_hand >= reserved)
);
CREATE INDEX inventory_items_variant_idx ON inventory_items(variant_id, warehouse_id);
CREATE INDEX inventory_items_available_idx ON inventory_items(available, variant_id) WHERE available > 0;

CREATE TABLE inventory_reservations (
    id VARCHAR(64) PRIMARY KEY,
    reservation_key VARCHAR(128) NOT NULL UNIQUE,
    actor_id VARCHAR(64) NOT NULL,
    cart_id VARCHAR(64),
    order_id VARCHAR(64),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','COMMITTED','CANCELLED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX inventory_reservations_expiry_idx ON inventory_reservations(status, expires_at) WHERE status = 'ACTIVE';

CREATE TABLE inventory_reservation_items (
    reservation_id VARCHAR(64) NOT NULL REFERENCES inventory_reservations(id) ON DELETE CASCADE,
    inventory_item_id VARCHAR(64) NOT NULL REFERENCES inventory_items(id),
    variant_id VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    PRIMARY KEY(reservation_id, inventory_item_id)
);
CREATE INDEX inventory_reservation_items_variant_idx ON inventory_reservation_items(variant_id, warehouse_id);

CREATE TABLE inventory_movements (
    id VARCHAR(64) PRIMARY KEY,
    inventory_item_id VARCHAR(64) NOT NULL REFERENCES inventory_items(id),
    movement_type VARCHAR(24) NOT NULL,
    quantity_delta BIGINT NOT NULL,
    on_hand_after BIGINT NOT NULL,
    reserved_after BIGINT NOT NULL,
    available_after BIGINT NOT NULL,
    reference_id VARCHAR(128),
    actor_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX inventory_movements_item_idx ON inventory_movements(inventory_item_id, created_at DESC);

CREATE TABLE inventory_adjustments (
    id VARCHAR(64) PRIMARY KEY,
    inventory_item_id VARCHAR(64) NOT NULL REFERENCES inventory_items(id),
    adjustment_type VARCHAR(16) NOT NULL CHECK (adjustment_type IN ('RESTOCK','DAMAGE','LOSS','CORRECTION','RETURN','TRANSFER')),
    quantity_delta BIGINT NOT NULL CHECK (quantity_delta <> 0),
    reason TEXT NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_inbox (
    event_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_outbox_events (
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
CREATE INDEX inventory_outbox_unpublished_idx ON inventory_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
