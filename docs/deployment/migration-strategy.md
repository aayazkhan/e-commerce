# Production database migration strategy

## Expand/contract

```text
review/query plan → expand nullable/additive schema → deploy compatible code
→ backfill in bounded batches → verify metrics/checksums → switch reads/writes
→ observe replication/locks → contract old columns/indexes in a later release
```

Flyway migrations are owned by each service. Application startup currently runs migrations, so production must either isolate migration execution to a controlled release job or explicitly prove that startup migration concurrency is safe for the target cluster. Do not allow every replica to perform an unreviewed long migration during a rollout.

## Required review

- lock duration and transaction scope;
- index build strategy (`CREATE INDEX CONCURRENTLY` where supported and appropriate);
- table size, backfill rate, replica impact, and disk headroom;
- old/new application compatibility and rollback path;
- migration duration on a production-like snapshot;
- post-migration invariant/reconciliation checks.

Migration tests required per release: fresh database, existing large database, concurrent traffic, failure/restart, and forward-compatible application rollback. No production-like migration timing or restore test was executed for Phase 9.
