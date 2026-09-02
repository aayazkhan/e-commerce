# Phase 6 — Downstream Commerce Services

Phase 6 adds four independently deployable Ktor services on ports 8096–8099. Each service owns its own PostgreSQL schema and has a separate Kafka consumer group. They consume the existing order, payment, shipping, refund and checkout event streams; no core commerce service calls any Phase 6 service synchronously.

## Services

- `notification-service`: consumes commerce events into an inbox and durable delivery queue. It provides FCM and APNs push provider implementations, HTTP email/SMS provider adapters, in-app notifications, localized templates, device registration, preferences, quiet hours, provider webhooks, deduplication, five-attempt exponential backoff and a permanent delivery DLQ.
- `review-service`: consumes order snapshots into local verified-purchase facts. Review writes validate that fact locally, enforce a per-user rate limit and a unique order/target constraint, then expose moderation, audit, reports, helpful votes, pagination and published rating aggregates. It never calls Order synchronously.
- `recommendation-service`: consumes behavior and order events into rule-based recently-viewed, frequently-bought-together, similar, trending and personalized projections. Redis is a 60-second best-effort cache; PostgreSQL remains the source of truth and `/api/v1/recommendations/fallback` serves popular IDs.
- `analytics-service`: consumes events into an idempotent inbox, a PII-minimized event journal, hourly/daily projections, product/seller metrics and revenue/funnel summaries. It stores a SHA-256 user hash and an allowlisted metric snapshot, not raw addresses, phones, emails, payment data or arbitrary payloads. Historical events can be replayed through the admin endpoint; projection markers make replay safe.

## Reliability boundaries

The shared `KafkaConsumerWorker` uses `enable.auto.commit=false`, `isolation.level=read_committed`, `auto.offset.reset=earliest` and commits only after the handler has persisted either its projection or a durable service-local DLQ record. Each service has an inbox primary key on `event_id`, so a Kafka redelivery or consumer restart is harmless.

Consumer lag and failure counters are exposed at `/metrics` (`*_consumer_lag_observed`, `*_consumer_failures`). A malformed event is recorded in the service DLQ and then committed; a temporary database failure leaves the offset uncommitted for recovery. Kafka and provider outages therefore affect only downstream projections/deliveries and cannot fail order, payment or checkout writes.

Notification delivery attempts use delays of 5, 10, 20, 40 and 80 seconds, capped by the five-attempt terminal transition. Provider message IDs and event/channel/user uniqueness prevent duplicate sends. In-app delivery is stored locally. Notification workers run outside request handlers, so no checkout path waits on a provider.

## Event topics

Default consumers subscribe to:

| Consumer | Topics |
| --- | --- |
| Notification | `order.events.v1`, `payment.events.v1`, `shipping.events.v1`, `refund.events.v1` |
| Review | `order.events.v1` |
| Recommendation | `catalog.events.v1`, `order.events.v1`, `behavior.events.v1` |
| Analytics | all commerce and behavior topics |

The event envelope and payload contract remain the Phase 5 contract in [`PHASE5_EVENTS.md`](PHASE5_EVENTS.md). Behavioral producers can publish `ProductViewed`, `SearchPerformed`, `CartItemAdded` and order events to `behavior.events.v1` without adding a recommendation dependency to checkout.

## Runtime artifacts

Each service has its own `Dockerfile`, `application.conf`, Flyway migration set and minimal OpenAPI contract. [`deploy/k8s/phase6-commerce.yaml`](../deploy/k8s/phase6-commerce.yaml) supplies separate Deployments and Services with readiness/liveness probes. `.env.phase6.example` lists the database, Kafka, Redis and provider configuration names. Provider credentials are environment/secret configuration only and are never persisted by these services.

## Acceptance test matrix

The durable inbox, offset boundary and worker tests should be run with Kafka and PostgreSQL Testcontainers in the `includeIntegration` profile:

1. Stop Kafka while creating an order/payment: the core write and its outbox commit succeed; downstream lag increases and later recovery consumes the event.
2. Return provider failures for notification sends: checkout remains independent, retries advance, the fifth failure is in `notification_dlq`, and no sixth send occurs.
3. Stop analytics consumers while orders are created: order creation succeeds; after restart, events are consumed from the committed offset.
4. Deliver the same envelope twice: notification inbox and analytics inbox/projection markers prevent duplicate work.
5. Create a review before the order snapshot is consumed: it is rejected as unverified; after `OrderPaid`/`OrderDelivered` is consumed, the same verified order can be reviewed.
6. Make Redis unavailable or return no recommendation rows: popular products are returned from PostgreSQL through the deterministic fallback path.
7. Replay a historical analytics range twice: both calls complete, but `analytics_applied_events` prevents double-counting.
8. Inspect `/metrics` during a blocked consumer: lag is non-zero and returns to zero after restart/recovery.
9. Check `analytics_events.metric_json`: only allowlisted amount/total/items fields are present and user identity is hashed.

The implementation has been compile-verified with the four new services and the shared Kafka module. External Kafka/PostgreSQL/Redis/provider integration and load/soak evidence require the project infrastructure to be running.
