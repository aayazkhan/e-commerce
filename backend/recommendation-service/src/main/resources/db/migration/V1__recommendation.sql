CREATE TABLE recommendation_inbox_events (event_id VARCHAR(120) PRIMARY KEY, event_type VARCHAR(120) NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE recommendation_behavior_events (id BIGSERIAL PRIMARY KEY, event_id VARCHAR(120) UNIQUE, user_id VARCHAR(120), session_id VARCHAR(120), event_type VARCHAR(80) NOT NULL, product_id VARCHAR(120), query_text VARCHAR(500), occurred_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX recommendation_behavior_user_idx ON recommendation_behavior_events(user_id,occurred_at DESC);
CREATE TABLE recommendation_popular_products (product_id VARCHAR(120) PRIMARY KEY, score NUMERIC NOT NULL DEFAULT 0, last_seen TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE recommendation_product_pairs (product_a VARCHAR(120) NOT NULL, product_b VARCHAR(120) NOT NULL, score BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(product_a,product_b));
CREATE TABLE recommendation_dlq (id BIGSERIAL PRIMARY KEY, event_id VARCHAR(120), error_text TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
