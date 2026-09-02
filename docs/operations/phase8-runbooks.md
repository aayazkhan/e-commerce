# Phase 8 operational runbooks

## Kafka lag or consumer outage

1. Check consumer group lag, pod restarts, rebalance count, error counter, and DLQ count.
2. Confirm producer order/payment requests remain healthy; do not make downstream consumers synchronous.
3. Pause a bad deployment, scale consumers only after checking partition count and downstream DB capacity.
4. Inspect a bounded sample of failed records; route poison records to DLQ with the original event ID.
5. Restart one consumer and verify offset recovery, idempotent writes, and lag slope.
6. Replay DLQ only after schema/handler correction and an isolated validation.

## Provider outage

1. Confirm timeout/error rate and provider status without logging credentials or payload PII.
2. Keep critical transactions in an explicit pending/retryable state; never unboundedly retry in request threads.
3. Let asynchronous notification/shipping/payment reconciliation workers drain through bounded retries.
4. Disable or route only the affected provider using a controlled configuration change.
5. Verify duplicate/idempotency behavior when the provider recovers.

## Database saturation

1. Check pool active/idle/pending, database CPU/IO, locks, slow queries, and replica lag.
2. Stop load generation and risky deploys; do not blindly increase pool size.
3. Identify the route/query and use an explain plan on a safe replica or restored copy.
4. Reduce concurrency or shed non-critical traffic; preserve checkout/order invariants.
5. Re-test at the smallest change and record before/after P95/P99 and connection usage.

## Rollback and graceful drain

1. Stop rollout and compare error/latency/lag against the release baseline.
2. Route traffic away from unhealthy pods through readiness; allow termination grace to drain HTTP and consumer work.
3. Roll back the immutable image/configuration; never roll back a database migration without a reviewed down/forward plan.
4. Verify outbox age, consumer offsets, payments, inventory, and order reconciliation.
