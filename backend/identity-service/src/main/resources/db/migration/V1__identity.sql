CREATE TABLE users (
    id VARCHAR(80) PRIMARY KEY,
    email VARCHAR(320),
    email_normalized VARCHAR(320),
    phone VARCHAR(32),
    phone_normalized VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    email_verified_at TIMESTAMPTZ,
    phone_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deactivated_at TIMESTAMPTZ,
    CONSTRAINT users_status_check CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'LOCKED', 'DEACTIVATED', 'DELETED')),
    CONSTRAINT users_email_or_phone_check CHECK (email_normalized IS NOT NULL OR phone_normalized IS NOT NULL),
    CONSTRAINT users_email_format_check CHECK (email_normalized IS NULL OR position('@' IN email_normalized) > 1)
);

CREATE UNIQUE INDEX users_email_unique ON users(email_normalized) WHERE email_normalized IS NOT NULL;
CREATE UNIQUE INDEX users_phone_unique ON users(phone_normalized) WHERE phone_normalized IS NOT NULL;
CREATE INDEX users_status_idx ON users(status);

CREATE TABLE user_credentials (
    user_id VARCHAR(80) PRIMARY KEY REFERENCES users(id),
    password_hash TEXT NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_roles (
    user_id VARCHAR(80) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE TABLE user_permissions (
    user_id VARCHAR(80) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission VARCHAR(128) NOT NULL,
    PRIMARY KEY (user_id, permission)
);

CREATE TABLE user_profiles (
    user_id VARCHAR(80) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE,
    gender VARCHAR(32),
    profile_image_url TEXT,
    preferred_language VARCHAR(16) NOT NULL DEFAULT 'en',
    preferred_currency CHAR(3) NOT NULL DEFAULT 'USD',
    marketing_email BOOLEAN NOT NULL DEFAULT FALSE,
    marketing_sms BOOLEAN NOT NULL DEFAULT FALSE,
    marketing_push BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_addresses (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label VARCHAR(16) NOT NULL,
    recipient_name VARCHAR(200) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    line1 VARCHAR(240) NOT NULL,
    line2 VARCHAR(240),
    city VARCHAR(120) NOT NULL,
    state VARCHAR(120) NOT NULL,
    postal_code VARCHAR(32) NOT NULL,
    country CHAR(2) NOT NULL,
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_addresses_label_check CHECK (label IN ('HOME', 'WORK', 'OTHER'))
);

CREATE INDEX user_addresses_user_idx ON user_addresses(user_id, updated_at DESC);
CREATE UNIQUE INDEX user_addresses_one_default_idx ON user_addresses(user_id) WHERE is_default;

CREATE TABLE user_sessions (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_family VARCHAR(80) NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL UNIQUE,
    device_id VARCHAR(160),
    platform VARCHAR(32),
    app_version VARCHAR(64),
    ip_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    last_active_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by VARCHAR(80),
    reuse_detected_at TIMESTAMPTZ
);

CREATE INDEX user_sessions_user_idx ON user_sessions(user_id, created_at DESC);
CREATE INDEX user_sessions_family_idx ON user_sessions(token_family);

CREATE TABLE otp_challenges (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL,
    destination_hash CHAR(64) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT otp_purpose_check CHECK (purpose IN ('PHONE_VERIFICATION', 'EMAIL_VERIFICATION', 'LOGIN', 'PASSWORD_RESET'))
);

CREATE INDEX otp_challenges_destination_idx ON otp_challenges(destination_hash, purpose, created_at DESC);

CREATE TABLE verification_tokens (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT verification_purpose_check CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

CREATE TABLE user_auth_providers (
    user_id VARCHAR(80) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider, subject),
    CONSTRAINT user_auth_provider_check CHECK (provider IN ('GOOGLE', 'APPLE'))
);

CREATE TABLE security_events (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80),
    event_type VARCHAR(64) NOT NULL,
    request_id VARCHAR(128),
    trace_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL,
    metadata_json TEXT NOT NULL
);

CREATE INDEX security_events_user_idx ON security_events(user_id, occurred_at DESC);

CREATE TABLE outbox_events (
    id VARCHAR(80) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx ON outbox_events(occurred_at) WHERE published_at IS NULL;
