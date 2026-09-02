CREATE TABLE categories (
    id VARCHAR(64) PRIMARY KEY,
    parent_id VARCHAR(64) REFERENCES categories(id) ON DELETE RESTRICT,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(160) NOT NULL UNIQUE,
    description TEXT,
    image_url TEXT,
    icon TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    seo_title VARCHAR(255),
    seo_description VARCHAR(500),
    seo_keywords JSONB NOT NULL DEFAULT '[]'::jsonb,
    canonical_url TEXT,
    path TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX categories_parent_status_order_idx ON categories(parent_id, status, sort_order, id);
CREATE INDEX categories_path_idx ON categories(path text_pattern_ops);
CREATE INDEX categories_status_idx ON categories(status, sort_order, id);

CREATE TABLE category_outbox_events (
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
CREATE INDEX category_outbox_unpublished_idx ON category_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
