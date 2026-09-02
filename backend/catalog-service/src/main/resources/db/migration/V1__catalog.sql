CREATE TABLE products (
    id VARCHAR(64) PRIMARY KEY,
    seller_id VARCHAR(64) NOT NULL,
    owner_type VARCHAR(16) NOT NULL CHECK (owner_type IN ('SELLER','ADMIN','SYSTEM')),
    brand_id VARCHAR(64),
    category_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    short_description VARCHAR(500),
    sku_reference VARCHAR(128),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','REVIEW','ACTIVE','INACTIVE','OUT_OF_STOCK','ARCHIVED')),
    tax_category VARCHAR(100),
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    seo_title VARCHAR(255),
    seo_description VARCHAR(500),
    canonical_url TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX products_sku_reference_uq ON products(sku_reference) WHERE sku_reference IS NOT NULL;
CREATE INDEX products_public_listing_idx ON products(status, created_at DESC, id DESC);
CREATE INDEX products_category_listing_idx ON products(category_id, status, created_at DESC, id DESC);
CREATE INDEX products_seller_idx ON products(seller_id, status, updated_at DESC);

CREATE TABLE product_variants (
    id VARCHAR(64) PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku VARCHAR(128) NOT NULL UNIQUE,
    barcode VARCHAR(128),
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    price_reference VARCHAR(128),
    weight_grams INTEGER CHECK (weight_grams IS NULL OR weight_grams > 0),
    dimensions JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX product_variants_product_idx ON product_variants(product_id, status, id);

CREATE TABLE product_media (
    product_id VARCHAR(64) NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    media_id VARCHAR(64) NOT NULL,
    media_type VARCHAR(20) NOT NULL,
    url TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    alt_text VARCHAR(255),
    PRIMARY KEY (product_id, media_id)
);
CREATE INDEX product_media_order_idx ON product_media(product_id, sort_order, media_id);

CREATE TABLE product_status_history (
    id VARCHAR(64) PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    changed_by VARCHAR(64) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE catalog_outbox_events (
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
CREATE INDEX catalog_outbox_unpublished_idx ON catalog_outbox_events(published_at, occurred_at) WHERE published_at IS NULL;
