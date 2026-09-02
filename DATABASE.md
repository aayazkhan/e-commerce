# Database Strategy

## Ownership

Each service owns its schema and migrations. No service reads another service’s tables. Cross-service data is obtained through APIs, events, or a purpose-built read model.

| Service | Primary store | Important data |
| --- | --- | --- |
| auth | PostgreSQL | users, identities, sessions, roles, devices, consent |
| catalog | PostgreSQL | products, variants, attributes, categories, brands, SEO metadata |
| inventory | PostgreSQL | warehouses, stock ledger, reservations, adjustments |
| cart | PostgreSQL | carts, lines, saved items, merge records |
| order | PostgreSQL | orders, lines, totals snapshot, transitions, returns |
| payment | PostgreSQL | intents, provider refs, webhook evidence, refunds, reconciliation |
| shipping | PostgreSQL | methods, shipments, tracking events, carrier refs |
| promotion | PostgreSQL | price books, rules, coupons, redemptions, loyalty ledger |
| review | PostgreSQL | reviews, ratings, moderation decisions |
| search | OpenSearch | denormalized catalog index and synonyms |
| media | PostgreSQL + object storage | asset metadata, variants, scan state |
| cms | PostgreSQL | pages, blocks, campaigns, publication versions |
| analytics | event lake/warehouse + PostgreSQL metadata | consented events, aggregates, report definitions |
| recommendation | feature store/read model | candidate sets, features, ranked recommendations |

Redis is never the system of record. It may hold bounded-TTL cache entries, rate-limit counters, short-lived locks, and ephemeral session material where approved.

For high-concurrency deployment, use a managed Redis Cluster or equivalent multi-zone sharded deployment, PgBouncer transaction pooling, bounded per-pod connection pools, and read replicas for consistency-tolerant reads. Payment, inventory reservation, order creation, and read-your-writes confirmation remain on the primary. See the [high-concurrency capacity and migration plan](docs/SCALABILITY_UPGRADE.md).

## Commerce invariants

- `order.total` is an immutable server-calculated snapshot after confirmation.
- `inventory.reservation` has a unique `(order_id, sku_id)` constraint and an expiry state.
- Payment provider event IDs are unique per provider and environment.
- Cart line mutations use an aggregate version to reject stale writes or apply a defined merge.
- Coupon redemption uses a transaction and a unique constraint for the applicable customer/order scope.
- Every tenant-scoped table includes tenant ID in primary or unique indexes.
- Audit records are append-only and contain actor, tenant, action, subject, reason, request ID, and timestamp.

## Migration policy

1. Every schema change is a reviewed, forward-only migration owned by its service.
2. Expand/ migrate/ contract is required for changes affecting rolling deploys.
3. Large backfills run as resumable jobs with progress checkpoints, not inside startup migrations.
4. Destructive changes require a retention and rollback plan; production drops happen only after an observation window.
5. Application code must tolerate one previous schema version during rollout.

## Consistency model

Local transactions protect a service’s invariants. Outbox rows are committed in the same transaction as the business change. A relay publishes them to Kafka; consumer inbox tables and unique event IDs prevent duplicate effects.

Strong consistency is required for price validation, inventory reservation, order creation, payment state, authorization, and refund state. Eventual consistency is acceptable for search, recommendations, analytics, notifications, and catalog projections.

## Indexing and retention

Indexes are derived from measured query plans. Hot paths receive bounded composite indexes such as `(tenant_id, user_id, updated_at)` and `(tenant_id, status, created_at)`. Order, payment, and audit data have explicit retention schedules; analytics event payloads are minimized and pseudonymized according to consent and regional policy.

## Backups and recovery

- managed PostgreSQL with encryption, automated backups, and point-in-time recovery;
- daily restore verification into an isolated account;
- quarterly disaster-recovery exercise for tier-1 services;
- documented RPO/RTO per environment;
- cross-region backup copies and restore drills targeting RPO <=5 minutes and RTO <=30 minutes for tier-1 services;
- OpenSearch and Redis are rebuildable projections or caches unless a service-specific exception is approved.
