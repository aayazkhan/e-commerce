# Backup/restore release gate

The backup provider and cloud account are not configured in this repository. The production operator must prove:

1. encrypted full backup plus WAL/PITR for each tier-1 PostgreSQL database;
2. retention, off-site/cross-region copy, and backup failure alerts;
3. isolated restore with known sentinels and application migration;
4. order/payment/inventory/refund/outbox invariants and event replay checks;
5. timed RPO/RTO, operator actions, and evidence artifact.

A successful backup job is not a restore result. See `docs/production-readiness/backup-restore-test.md` and `docs/production-readiness/dr-test.md`.
