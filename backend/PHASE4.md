# Phase 4 — Transaction-safe commerce core

Implemented services:

- `inventory-service` (8087): PostgreSQL-owned stock, atomic `available >= quantity` reservations, normalized reservation lines, idempotency, database-driven expiry worker using `SKIP LOCKED`, release/commit transitions, adjustments, movements, low-stock and out-of-stock outbox events.
- `cart-service` (8088): durable guest/authenticated carts, SHA-256 guest-token ownership, quantity limits, price snapshots, live pricing/inventory validation, deterministic merge, cache acceleration, expiry/abandoned-cart events, and mutation idempotency.
- `wishlist-service` (8089): JWT-owned wishlists, database uniqueness, cursor pagination, live pricing/availability enrichment, and outbox events.
- `promotion-service` (8090): percentage, fixed, product/category, buy-X-get-Y and free-shipping calculations; product/category/seller/customer-segment rules; promotion/coupon administration; atomic usage counters; reserved/committed/released redemptions; and idempotent apply/remove lifecycle.

Each service owns PostgreSQL tables and a transactional outbox. Redis is a best-effort accelerator and never authorizes inventory or coupon usage. Kafka event publication is retryable from the outbox; consumers/inbox tables are reserved for the Phase 5 workflow consumers.

Deployment contracts are in each service's `openapi.yaml`, `Dockerfile`, and `../deploy/k8s/phase4-commerce.yaml`. Use `.env.phase4.example` as the environment contract.

## Validation status

The four services compile together and have focused unit tests for quantity, ownership, promotion math, and line totals. Full `test`, distribution, migration, container, concurrency, E2E, Kafka, Redis, failure-recovery, and 200K-load validation remains environment-dependent when PostgreSQL/Redis/Kafka and Docker are not available. Those checks must be run before production rollout; no external-infrastructure success is claimed here.

Critical Phase 4 guarantees are enforced in the database: inventory reservations update stock with a conditional atomic SQL update inside a transaction, coupon/promotion counters are updated with conditional atomic SQL updates under locked promotion rows, cart/wishlist ownership is derived from JWT or a hashed guest token, and all durable mutations write outbox records in the same transaction.
