# Identity service

The Phase 2 implementation consolidates Auth/Identity, User, and Customer Profile into one independently deployable service. Credentials, sessions, user lifecycle, profile, and addresses share one transaction boundary and database owner; the package structure keeps application/domain/infrastructure concerns separate so a later service split can use APIs and events without sharing tables.

## Implemented endpoints

```text
POST   /api/v1/auth/register
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout
GET    /api/v1/auth/sessions
DELETE /api/v1/auth/sessions/{sessionId}
DELETE /api/v1/auth/sessions
POST   /api/v1/auth/email/verify
POST   /api/v1/auth/password/forgot
POST   /api/v1/auth/password/reset
POST   /api/v1/auth/otp/request
POST   /api/v1/auth/otp/verify
GET    /api/v1/users/me
DELETE /api/v1/users/me
GET    /api/v1/profile
PATCH  /api/v1/profile
GET    /api/v1/addresses
POST   /api/v1/addresses
PATCH  /api/v1/addresses/{addressId}
DELETE /api/v1/addresses/{addressId}
POST   /api/v1/addresses/{addressId}/default
```

## Security behavior

- Argon2id password hashes with per-password salts;
- HMAC JWT access tokens with issuer, audience, expiry, token ID, roles, permissions, and configured key IDs;
- multi-key validation supports signing-key rotation;
- opaque refresh tokens are stored only as SHA-256 hashes and rotated transactionally;
- refresh-token reuse revokes the token family;
- login/password-reset/OTP limits use Redis counters with TTL;
- OTPs and verification secrets are hashed, purpose-scoped, one-time, short-lived, and attempt-limited;
- challenge delivery uses a configured HTTPS internal delivery endpoint and never publishes OTPs/passwords/tokens to Kafka;
- user/resource identity comes from the verified access token, not request bodies;
- PostgreSQL constraints enforce unique email/phone and one default address per user;
- user/account/security changes write security records and important domain changes write transactional outbox rows.

## Required runtime configuration

See [.env.example](../../.env.example). Production requires non-empty database, Redis, Kafka, JWT key, tenant, and challenge-delivery configuration from a secret manager. The challenge endpoint is an internal authenticated adapter to the notification/delivery platform; it is not a fake local sender.

## Tests

```text
./gradlew :backend:identity-service:test
RUN_IDENTITY_INTEGRATION_TESTS=true ./gradlew :backend:identity-service:test
```

The integration test is opt-in because it requires Docker and starts a PostgreSQL Testcontainer. Redis/Kafka contract tests are added when the local integration profile is wired in the next infrastructure increment.
