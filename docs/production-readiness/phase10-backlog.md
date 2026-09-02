# Phase 10 backlog

Priorities are evidence-driven and must be re-ranked after staging telemetry.

## P0 — production blockers

- Deploy a complete gateway/ingress route contract and validate auth, WAF, rate limiting, request size, and TLS.
- Provision production-like staging and run 10K→25K→50K→100K→200K load gates with measured capacity.
- Execute encrypted backup restore and timed DR drills; record achieved RPO/RTO.
- Run payment/inventory/order/refund reconciliation after provider, Kafka, DB, Redis, and pod failures.
- Assign pager ownership and verify critical alert firing/runbook links.

## P1 — significant reliability/security

- Select and implement provider-specific Terraform/OpenTofu modules and remote state controls.
- Configure External Secrets Operator/workload identity, JWT overlap rotation, TLS renewal, and registry immutability/signing.
- Add real OpenTelemetry/Prometheus exporter wiring, dashboards, and baseline-relative canary aborts.
- Run DAST/IDOR/mass-assignment/input-abuse/object-storage/search-isolation tests and triage all high/critical findings.
- Add breaking OpenAPI/event compatibility checks and provider sandbox contract suites.
- Isolate Flyway production migration jobs and benchmark large-table/backfill/index plans.

## P2 — measured optimization

- Calibrate DB pool/read-replica/PgBouncer settings from connection and query telemetry.
- Tune Kafka partitions/consumer parallelism, Redis hot keys/evictions, OpenSearch shards, and HPA signals from load evidence.
- Add CDN/media lifecycle and analytics retention/archival according to legal/business policy.

## P3 — optional evolution

- Multi-region active/standby, advanced fraud detection, ML recommendation models, deeper FinOps, and global traffic routing only if measured traffic and recovery objectives justify them.
