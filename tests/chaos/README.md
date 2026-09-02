# Chaos and recovery harness

Fault injection is intentionally environment-specific. The Phase 8 test owner must supply the staging namespace, service-account permissions, Kafka/PostgreSQL/Redis provider controls, and a rollback command before running an experiment.

Required experiments are recorded in `docs/production-readiness/chaos-test-results.md` and `docs/operations/game-day-plan.md`. Every run must capture:

- exact fault, selector, duration, and blast radius;
- HTTP error/latency, outbox age, Kafka lag/DLQ, database pool, Redis, and provider metrics;
- order/payment/inventory/review/notification/analytics reconciliation counts;
- recovery time and any records requiring manual repair.

Do not inject faults into production until a staging run has verified the selector and rollback.
