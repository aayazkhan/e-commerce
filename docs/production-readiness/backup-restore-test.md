# Backup and restore test

Status: **NOT RUN — backup provider, retention, and restore environment are not configured in this repository**

## Required policy

| Data | Backup | Target RPO | Target RTO | Restore validation |
|---|---|---:|---:|---|
| PostgreSQL service databases | Encrypted full + continuous/WAL strategy | ≤ 15 min | ≤ 60 min | Restore to isolated instance; migrations; row/count/checksum and invariant checks |
| Redis | Managed snapshots/AOF according to cache criticality | Cache-dependent; no source of truth | ≤ 30 min | Restore only if required; verify cache rebuild and fail-open behavior |
| Kafka | Replication plus topic retention; archive critical events | ≤ 5 min | ≤ 60 min | Recreate consumer offsets and replay into an isolated sink |
| OpenSearch | Snapshots/reindex source of truth | ≤ 24 h | ≤ 4 h | Restore index and compare document counts/checksums |
| Object storage | Versioning + cross-region replication | ≤ 15 min | ≤ 4 h | Restore representative objects and signed URL behavior |

## Test steps

1. Create a timestamped source snapshot and record encryption/key IDs.
2. Write known sentinel records and capture counts/invariants.
3. Restore into an isolated environment with no production writes.
4. Apply the exact application version and migrations.
5. Validate ownership, order/payment/inventory invariants, event replay idempotency, and object references.
6. Measure restore duration and data loss window.
7. Record operator, evidence links, failures, and follow-up actions.

Targets are objectives, not achieved results.
