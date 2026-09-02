# Backend implementation status

The repository contains the buildable backend through Phase 7, Phase 8 hardening, and Phase 9 deployment/release scaffolding. The original foundation includes:

- Gradle Kotlin/JVM build with pinned Kotlin/Ktor versions;
- shared common primitives for IDs, money, pagination, and request metadata;
- shared API error model;
- authorization roles/permissions and principal metadata;
- database, Redis, Kafka, and observability boundary types;
- buildable Ktor API gateway with correlation IDs, safe error responses, CORS allow-list, health endpoints, and metrics endpoint;
- unit tests for common invariants and a gateway health test.

Phase 2 adds a real consolidated [identity-service](identity-service/README.md) for authentication, user lifecycle, profiles, addresses, sessions, Argon2id password hashing, JWT key rotation, refresh-token rotation/reuse detection, Redis rate limiting, PostgreSQL migrations, and transactional outbox records.

Phase 3 now adds independently deployable [category-service](category-service/openapi.yaml), [catalog-service](catalog-service/openapi.yaml), [pricing-service](pricing-service/openapi.yaml), [media-service](media-service/openapi.yaml), and [search-service](search-service/openapi.yaml). These use PostgreSQL as the transactional source of truth, Redis for bounded read caching, Kafka transactional outboxes for propagation, S3-compatible object storage for media, and OpenSearch as a derived product index.

Phase 3 implementation notes:

- Category hierarchy uses a materialized path for one-query tree reads and validates parent changes against descendant paths.
- Catalog stores products, variants, attributes, media references, status history, and seller ownership; inventory quantities remain outside catalog.
- Pricing stores minor-unit amounts, effective windows, tax basis points, and immutable version snapshots.
- Media stores metadata/jobs in PostgreSQL and performs signature validation, presigned uploads, object storage, and asynchronous image derivatives.
- Search consumes catalog/pricing events idempotently, supports alias-based reindexing, and fails with a controlled dependency error when OpenSearch is unavailable.

Redis/Kafka/OpenSearch/Testcontainers E2E validation, production deployment, provider sandboxes, backup/restore, and 200K-user load evidence require external environments and are not claimed by the default unit test run. Deployment/release contracts are documented in [../DEPLOYMENT.md](../DEPLOYMENT.md) and [../docs/production-readiness/phase9-go-live-report.md](../docs/production-readiness/phase9-go-live-report.md); no service should be represented by a fake repository or mock business response.

Phase 10 adds JaCoCo aggregation, a hard 100% line/branch gate, separated integration/E2E Gradle lanes, and evidence documents under [../docs/phase10](../docs/phase10). The default `test` lane currently passes its 42 executed tests, while the coverage gate and infrastructure-dependent lanes remain intentionally incomplete as recorded in the [final report](../docs/phase10/final-test-report.md).

Phase 4 adds the transaction-safe commerce core. See [PHASE4.md](PHASE4.md) for service contracts, deployment files, event boundaries, correctness guarantees, and environment-dependent validation status.
