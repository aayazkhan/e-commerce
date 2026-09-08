# Phase 10 integration lane

Integration tests use real dependency semantics where mocks would hide correctness:

- PostgreSQL/Testcontainers for migrations, constraints, transactions, locks, and rollback;
- Kafka/Testcontainers for serialization, consumer groups, offset commits, retries, DLQs, replay, and lag;
- Redis/Testcontainers for TTL, invalidation, rate limits, and reconnect behavior;
- OpenSearch/provider stubs for index aliases, search failures, signed webhooks, and timeout mapping.

Run the tagged lane only when Docker and the required environment variables are available:

```bash
./gradlew integrationTest -PrunIntegration=true --no-daemon
```

The six migration tests require `RUN_PHASE3_INTEGRATION_TESTS=true` (identity uses `RUN_IDENTITY_INTEGRATION_TESTS=true`). Inventory and promotion concurrency tests start Testcontainers directly. A local run without Docker is not evidence of integration success.

## Local dependency stack

Each `@Tag("integration")` test starts its own Postgres container directly via
Testcontainers (see `InventoryConcurrencyIntegrationTest.kt` for the pattern) — Docker
is the only prerequisite, no shared stack needs to be running first.

For manually running/exploring the full system locally (e.g. starting api-gateway plus
real services and hitting them through it), a separate docker-compose stack is available
at [`infrastructure/docker/docker-compose.yml`](../../infrastructure/docker/docker-compose.yml):
Postgres (pre-seeded with every service's role/database, matching `.env.example` /
`.env.phase{6,7}.example` / `backend/.env.phase{4,5}.example` exactly), Redis, a
single-broker KRaft Kafka, OpenSearch, and MinIO (S3-compatible object storage, with
`commerce-local` and `commerce-media` buckets pre-created). Its service names match the
Kubernetes Service names in `infrastructure/kubernetes` / `deploy/k8s`, so the same
`http://<service-name>:<port>` default the api-gateway and services fall back to when no
`*_URL` environment override is set resolves correctly against either this compose
network or a real cluster.

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
docker compose -f infrastructure/docker/docker-compose.yml down -v   # wipe all data
```

This stack is for local dev and manual verification, not for the automated integration
lane above, and is not a substitute for it — it has no TLS, uses `trust` Postgres auth,
and runs single-node/single-broker with no durability guarantees.

`org.testcontainers:kafka` (`libs.testcontainers.kafka`) is available for writing
Kafka-backed `@Tag("integration")` tests (consumer idempotency, retry/DLQ, replay).
There is no dedicated OpenSearch Testcontainers module in this catalog; use
`org.testcontainers:testcontainers`' `GenericContainer` with the
`opensearchproject/opensearch:2.19.1` image (pulled in transitively by
`testcontainers-postgresql`/`testcontainers-junit`, so no extra dependency is needed).
