# Phase 5 — Order, Payment, Shipping, Refund, and Checkout Saga

## Delivered

- `order-service` (8091) owns immutable order/item/address/price/promotion snapshots, the complete order state matrix, status history, cancellation and return records, customer/admin APIs, inbox/outbox tables, idempotency hashes, and internal Saga status transitions.
- `payment-service` (8092) owns payment intents, attempts, authorization/capture/refund transactions, provider webhooks, reconciliation runs, inbox/outbox tables, refundable-balance enforcement, and provider-token-only payment requests. Raw card data is not accepted or persisted.
- `shipping-service` (8093) owns provider quotes, shipments, addresses/items, tracking events, return-shipment records, HMAC-verified webhooks, inbox/outbox tables, and provider-backed rate/label/tracking operations.
- `refund-service` (8094) owns requested/approved/processing/completed/rejected refunds, item-level records, status history, reconciliation records, inbox/outbox tables, and calls payment-service for the authoritative refundable amount.
- `checkout-service` (8095) is the only checkout orchestrator. It validates cart state, retrieves server-owned addresses, recalculates pricing/promotion/shipping totals, reserves inventory, creates the order snapshot, applies a promotion redemption, creates payment, commits the redemption/inventory, creates shipping, and persists each Saga checkpoint.

## Contracts and invariants

All monetary values are integer minor units with an explicit three-letter currency. Client requests contain identifiers and payment-provider tokens only; checkout does not trust client prices, discounts, stock, shipping costs, product names, or address contents. Catalog, pricing, identity, cart, promotion, inventory, payment, and shipping responses are re-read server-side and snapshot into the order.

The order state machine is explicit in `OrderDomain.kt`: `CREATED → VALIDATING → INVENTORY_RESERVED → PAYMENT_PENDING → PAYMENT_PROCESSING → PAID → CONFIRMED → PROCESSING → SHIPPED → OUT_FOR_DELIVERY → DELIVERED`, with controlled cancellation, return, refund, and failure branches. Payment and shipment matrices similarly reject illegal skips. PostgreSQL row locks, unique idempotency keys, conditional inventory SQL updates, unique provider events, and transactional outbox inserts provide the durable concurrency boundaries.

The checkout Saga uses `checkout_sagas`, `checkout_saga_steps`, and `checkout_idempotency`. Repeated checkout keys return the existing Saga; provider operations use derived idempotency keys. Inventory release, promotion release, order refund-pending transition, and payment refund are compensation hooks for failure after external work. A retry repeats only idempotent operations and can resume a provider-confirmed payment after webhook/reconciliation.

## Runtime and security

Each service has an independent Flyway migration location, PostgreSQL connection pool, health/readiness endpoints, structured request IDs, JWT ownership checks, internal service-token checks, Kafka transactional-outbox publisher, and OpenAPI contract. Redis remains an optional accelerator; no authorization, stock, payment, or promotion decision relies on cache state. HTTP provider adapters require API credentials and webhook HMAC secrets; unset provider credentials fail closed with a retryable dependency error.

Container definitions are in each service directory. `deploy/k8s/phase5-commerce.yaml` provides separate Deployments, Services, readiness/liveness probes, HPA policies, and PDBs. Use `.env.phase5.example` as the variable contract and replace every example secret with a managed secret.

See `PHASE5_EVENTS.md` for the envelope and event catalog. Phase 6 should add broker consumers for order/payment/shipping/refund projections, dead-letter/replay tooling, a real checkout recovery worker with durable service credentials, provider sandbox contract tests, gateway route registration, distributed tracing/metrics dashboards, and production-like 200K-user load/soak evidence.

## Verification and limits

Focused state-machine tests and Kotlin compilation pass for all five services. The Gradle test suite and distribution build should be run as a release gate. Docker is not installed in the current environment, so PostgreSQL/Redis/Kafka/Testcontainers migrations, provider webhooks, container startup, E2E recovery, and 2-lakh-user load/soak results are intentionally not claimed. Run those checks with the real provider sandbox and production-like infrastructure before rollout.
