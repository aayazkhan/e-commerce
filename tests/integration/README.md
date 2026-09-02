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
