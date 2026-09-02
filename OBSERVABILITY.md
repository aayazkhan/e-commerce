# Observability

## Signals

- structured JSON logs with severity, service, environment, version, tenant-safe context, request ID, trace ID, and event ID;
- OpenTelemetry traces across gateway, service, database, event publish, consumer, and provider calls;
- Prometheus metrics for request rate, latency, errors, saturation, queue lag, outbox age, reservation failures, payment failures, and checkout conversion;
- business metrics separated from operational metrics and consent-gated where user behavior is involved.

## Correlation

The gateway accepts or creates `X-Request-Id` and propagates W3C trace context. Events carry correlation and causation IDs. Consumer logs include event ID, partition/offset, attempt count, and outcome. Sensitive payloads are represented by redacted hashes or safe identifiers.

## SLOs and alerts

Page on availability/error budget burn, checkout failure spikes, payment webhook lag, inventory reservation failures, database saturation, Kafka lag, outbox age, and certificate/secret expiry. Ticket on slow trends and projection lag. Dashboards should support a user journey view and a service dependency view.

## Operational requirements

Each service ships a dashboard, alert rules, runbook link, ownership label, dependency timeout policy, and graceful-degradation description. Logs and traces have retention by data classification. Sampling may reduce trace volume, but all errors and checkout/payment traces remain queryable under the retention policy.

For the high-concurrency target, dashboards must additionally expose active connections, RPS per pod, pool wait, cache hit ratio/evictions/hot keys, Kafka lag/ISR/DLQ rate, replica lag, outbox age, OpenSearch rejected tasks, and cost per million requests. The [scalability upgrade](docs/SCALABILITY_UPGRADE.md) defines the initial alert budgets and benchmark gates.

Client dashboards additionally segment web vitals, JS errors, API request volume, cache hit/miss, offline queue age, sync conflicts, startup/first-frame time, screen render time, memory, ANR/crash-free users, battery/network type, app version, OS, device class, country, and consent state. Client telemetry is sampled and privacy-minimized; it must never block a user command.
