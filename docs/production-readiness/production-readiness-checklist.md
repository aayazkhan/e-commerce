# Production-readiness checklist

Legend: `[x]` verified in repository, `[~]` implemented but environment evidence pending, `[ ]` not complete.

## Architecture and capacity

- [x] Service ownership and downstream dependency direction documented.
- [x] Capacity formulas and assumptions documented.
- [ ] Query-plan/index/lock audit completed against production-like data.
- [ ] Capacity model calibrated with measured traffic.
- [ ] 10K, 50K, 100K, and 200K progressive load stages passed.

## Runtime resilience

- [x] Cursor page size is bounded.
- [~] Shared DB connection timeout and pool lifecycle controls are present; target pool sizing is unvalidated.
- [~] Outbound provider timeouts are bounded in reviewed paths; uniform circuit-breaker/retry policy is pending.
- [x] Health live/ready endpoints and shutdown cleanup exist in reviewed services.
- [ ] Retry storm, bulkhead, graceful drain, and dependency outage behavior tested.

## Messaging and data

- [x] Outbox/consumer idempotency patterns are present in reviewed services.
- [~] Kafka lag/failure counters exist; scrape/alert/replay evidence is pending.
- [ ] Poison-record and bounded-DLQ behavior verified under live broker conditions.
- [ ] Database, Redis, OpenSearch, and object-store restore tests passed.
- [ ] Payment/inventory/order reconciliation passed after injected failures.

## Security

- [x] Route-level JWT/permission checks are present in reviewed Phase 7 services.
- [~] Secrets are configuration-driven; secret scan and rotation evidence pending.
- [ ] IDOR, mass assignment, input abuse, money/invariant, SSRF, object-storage, and search isolation tests passed.
- [ ] Dependency, image, SAST, DAST, and penetration-test findings triaged.

## Kubernetes and delivery

- [x] Phase 7 resources include probes, requests/limits, HPA, and PDBs.
- [~] Phase 8 hardening overlay adds shutdown/topology/network controls; cluster validation pending.
- [ ] Immutable image digests, signing, admission policy, and rollback drill verified.
- [ ] CI gates build/tests/manifests/scans and publishes evidence.

## Operations

- [~] SLO/SLI targets and runbook ownership documented.
- [ ] Dashboards, paging alerts, Kafka lag, outbox age, DLQ, provider failures, and reconciliation alerts verified.
- [ ] Game day completed with incident notes and corrective actions.
- [ ] Production go/no-go signed by engineering, security, SRE, and business owners.
