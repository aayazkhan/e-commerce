CREATE TABLE audit_inbox_events (event_id VARCHAR(120) PRIMARY KEY, event_type VARCHAR(120) NOT NULL, processed_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE audit_events (id VARCHAR(120) PRIMARY KEY, actor_id VARCHAR(120), actor_type VARCHAR(40) NOT NULL, action VARCHAR(120) NOT NULL, resource_type VARCHAR(80) NOT NULL, resource_id VARCHAR(160) NOT NULL, seller_id VARCHAR(120), before_json JSONB, after_json JSONB, reason VARCHAR(2000), request_id VARCHAR(120), trace_id VARCHAR(180), ip_hash VARCHAR(128), user_agent VARCHAR(500), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE audit_dlq (id BIGSERIAL PRIMARY KEY, event_id VARCHAR(120), error_text TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX audit_search_idx ON audit_events(created_at DESC,action,resource_type,resource_id);
CREATE INDEX audit_actor_idx ON audit_events(actor_id,created_at DESC);
CREATE INDEX audit_seller_idx ON audit_events(seller_id,created_at DESC);
CREATE OR REPLACE FUNCTION prevent_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit_events is append-only'; END; $$;
CREATE TRIGGER audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
