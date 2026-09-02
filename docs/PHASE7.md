# Backend Phase 7

Phase 7 adds five independently deployable bounded contexts:

| Service | Port | Owns | Kafka topic |
| --- | ---: | --- | --- |
| seller-service | 8100 | seller lifecycle, seller projection, ledger and settlement foundation | `seller.events.v1` |
| cms-service | 8101 | pages, immutable versions, workflow and schedules | `cms.events.v1` |
| audit-service | 8102 | append-only audit records, event projection and DLQ | consumer only |
| feature-flag-service | 8103 | versioned flags and deterministic evaluation | `feature-flags.events.v1` |
| admin-service | 8104 | authorized orchestration and durable bulk jobs | `admin.events.v1` |

## Dependency boundary

Admin delegates to domain APIs. Seller delegates product and inventory mutations to catalog/inventory and derives the seller identity from the verified JWT. CMS, audit and flags own their own storage. No order, payment, inventory or checkout request depends on any Phase 7 service. Kafka consumers and outbox publishers are asynchronous; an unavailable Kafka broker does not roll back a core-commerce transaction.

## Security invariants

- Seller routes never trust a caller-supplied seller ID for authorization. The seller context comes from the verified JWT subject and the seller membership table.
- Seller product patch and inventory mutation first verify ownership against catalog data, preventing Seller A from mutating Seller B resources.
- Admin routes require explicit permission claims such as `ADMIN_SELLER_UPDATE`, `ADMIN_CMS_PUBLISH`, `ADMIN_AUDIT_READ` and `ADMIN_FEATURE_FLAG_UPDATE`.
- CMS content is sanitized before version storage. Page updates use an optimistic version check; published versions cannot be silently overwritten.
- Audit rows are insert-only at the application layer and protected by a database trigger against update/delete. Secrets, tokens, passwords, OTPs, CVV and card numbers are removed before persistence; IP values are hashed.
- Flags default to the fail-safe value when disabled, unavailable, environment-mismatched, untargeted or outside the rollout bucket. Buckets use SHA-256 of `flag:key + identity` and are stable across restarts.

## Operational behavior

- Every service exposes `/health/live`, `/health/ready` and `/metrics`.
- Kafka consumers use manual commits, `read_committed`, inbox deduplication, replay-safe projections and observable lag/failure counters.
- Seller consumer failures are persisted to `seller_dlq`; audit consumer failures are persisted to `audit_dlq` before the Kafka offset is committed.
- Admin bulk jobs persist every item, claim work with row locks, retry failures up to five attempts, recover stale running items, and expose item/job status through `/api/v1/admin/jobs/{id}`.
- CMS schedules are database-backed and processed by a scheduler loop, so schedules survive process restarts.
- Redis is a cache only. CMS serves from PostgreSQL on cache miss; flags return a safe default if evaluation cannot read storage.

Admin permissions are intentionally granular: `ADMIN_USER_*`, `ADMIN_SELLER_*`, `ADMIN_PRODUCT_*`, `ADMIN_ORDER_*`, `ADMIN_PAYMENT_READ`, `ADMIN_REFUND_*`, `ADMIN_INVENTORY_*`, `ADMIN_REVIEW_*`, `ADMIN_CMS_*`, `ADMIN_ANALYTICS_READ`, `ADMIN_AUDIT_READ` and `ADMIN_FEATURE_FLAG_*`. `SUPER_ADMIN` and `PLATFORM_ADMIN` are privileged roles; support and operational roles still require the relevant permission claim.

## Important endpoints

- Seller: `/api/v1/seller`, `/api/v1/seller/products`, `/api/v1/seller/orders`, `/api/v1/seller/inventory/{variantId}`, `/api/v1/seller/ledger`.
- CMS: public `/api/v1/cms/pages/{slug}` and permissioned `/api/v1/admin/cms/pages/...` workflow endpoints.
- Flags: public `POST /api/v1/feature-flags/evaluate`; permissioned CRUD under `/api/v1/admin/feature-flags`.
- Audit: permissioned search at `GET /api/v1/admin/audit` and immutable record insertion at `POST /api/v1/admin/audit`.
- Admin: dashboard proxies, domain-admin proxies, durable `POST /api/v1/admin/products/bulk-publish`, and job status.

## Verification

```bash
./gradlew :backend:admin-service:test :backend:seller-service:test :backend:cms-service:test :backend:audit-service:test :backend:feature-flag-service:test
./gradlew :backend:admin-service:installDist :backend:seller-service:installDist :backend:cms-service:installDist :backend:audit-service:installDist :backend:feature-flag-service:installDist
```

The service tests cover CMS XSS stripping, deterministic flag buckets and seller lifecycle bypass prevention. Integration tests should run with PostgreSQL, Kafka and Redis to exercise the outage/replay matrix described in the Phase 7 acceptance specification.
