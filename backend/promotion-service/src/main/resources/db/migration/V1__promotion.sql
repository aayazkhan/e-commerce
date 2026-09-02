CREATE TABLE promotions (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    type VARCHAR(32) NOT NULL CHECK (type IN ('PERCENTAGE','FIXED_AMOUNT','PRODUCT_DISCOUNT','CATEGORY_DISCOUNT','BUY_X_GET_Y','FREE_SHIPPING')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','ACTIVE','PAUSED','EXPIRED','ARCHIVED')),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ,
    currency CHAR(3) NOT NULL,
    min_order_minor BIGINT NOT NULL DEFAULT 0 CHECK (min_order_minor >= 0),
    max_discount_minor BIGINT,
    percentage_bps INTEGER CHECK (percentage_bps IS NULL OR percentage_bps BETWEEN 1 AND 10000),
    fixed_amount_minor BIGINT CHECK (fixed_amount_minor IS NULL OR fixed_amount_minor > 0),
    buy_quantity INTEGER,
    get_quantity INTEGER,
    product_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    category_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    seller_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    customer_segments JSONB NOT NULL DEFAULT '[]'::jsonb,
    usage_limit BIGINT,
    usage_count BIGINT NOT NULL DEFAULT 0,
    per_user_limit BIGINT,
    stack_policy VARCHAR(24) NOT NULL DEFAULT 'NO_STACK' CHECK (stack_policy IN ('NO_STACK','BEST_DISCOUNT_ONLY','PRIORITY_BASED')),
    priority INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX promotions_active_idx ON promotions(status,start_at,end_at);
CREATE TABLE coupons (
    id VARCHAR(64) PRIMARY KEY,
    promotion_id VARCHAR(64) NOT NULL REFERENCES promotions(id),
    code VARCHAR(64) NOT NULL,
    normalized_code VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','EXPIRED')),
    usage_limit BIGINT,
    usage_count BIGINT NOT NULL DEFAULT 0,
    per_user_limit BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX coupons_promotion_idx ON coupons(promotion_id);
CREATE TABLE promotion_redemptions (
    id VARCHAR(64) PRIMARY KEY,
    promotion_id VARCHAR(64) NOT NULL REFERENCES promotions(id),
    coupon_id VARCHAR(64) REFERENCES coupons(id),
    user_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    discount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','COMMITTED','RELEASED','CANCELLED')),
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(user_id,idempotency_key)
);
CREATE INDEX promotion_user_usage_idx ON promotion_redemptions(user_id,promotion_id,status);
CREATE TABLE promotion_outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX promotion_outbox_unpublished_idx ON promotion_outbox_events(published_at,occurred_at) WHERE published_at IS NULL;
CREATE TABLE promotion_inbox_events (event_id VARCHAR(128) PRIMARY KEY, event_type VARCHAR(100) NOT NULL, processed_at TIMESTAMPTZ NOT NULL);
