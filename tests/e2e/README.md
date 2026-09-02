# Phase 10 E2E environment

The E2E lane must run against a disposable, reproducible environment containing:

- PostgreSQL with all service migrations;
- Redis;
- Kafka with commerce topics, consumer groups, retry topics, and DLQs;
- OpenSearch (or the versioned compatible search image);
- S3-compatible object storage;
- API gateway and all backend service images;
- provider stubs or explicitly configured payment, shipping, email/SMS, push, OAuth, and webhook sandboxes.

No E2E test may depend on manually seeded records. A test creates its tenant, user, seller, product, inventory, cart, and provider fixtures, asserts the result, and removes or isolates them by run identifier. Use unique idempotency keys and a deterministic clock per run.

## Required flows

1. Registration → verification → login → refresh → logout.
2. Login → browse → search → product → cart → wishlist.
3. Cart → address → promotion → shipping → payment → order → tracking.
4. Delivered order → return request → approval → pickup/inspection → refund.
5. Seller onboarding → verification → product → inventory → fulfillment → settlement.
6. Admin authorization → user/seller/product/order/refund/audit operations.

## Required failure runs

Kill or isolate checkout, payment, and inventory consumers; restart Kafka, Redis, and PostgreSQL; delay provider responses; replay webhooks and Kafka events. Assert no duplicate payment/order/refund, no overselling, compensation state, DLQ termination, and observable correlation IDs.

The Gradle E2E task is opt-in:

```bash
./gradlew e2eTest -PrunE2e=true -PincludeE2e=true --no-daemon
```

There are currently no tests tagged `e2e`; this README defines the environment contract and does not represent an E2E pass.
