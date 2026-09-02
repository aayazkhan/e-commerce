# Load-test results

Status: **NOT RUN — no load-test environment or result artifacts were available**

## Progressive plan

Execute in order and stop on the first failed gate:

| Stage | Shape | Pass gate |
|---|---|---|
| Smoke | 1–10 users, 10 min | Functional success, no unexpected 5xx |
| 10K | 10,000 concurrent sessions, ramp 30 min | SLOs met, no pool/queue saturation |
| 50K | 50,000 concurrent sessions | Error budget burn acceptable, Kafka lag recovers |
| 100K | 100,000 concurrent sessions | No sustained CPU throttling or DB connection exhaustion |
| 200K | 200,000 concurrent sessions | Only claim support if all SLO/resource/recovery gates pass |

Scenarios: normal browsing, flash sale/hot product, coupon contention, search, cart, checkout/order/payment, pagination on growing data, Kafka catch-up, and one dependency degraded. Use immutable scenario data and idempotency keys for writes.

## Required artifacts

- k6 summary JSON and thresholds.
- Per-service RPS/latency/error graphs.
- Pod CPU/memory/throttle/restarts and HPA events.
- PostgreSQL pool/lock/slow-query report; Redis memory/hit/eviction report.
- Kafka producer/consumer throughput, lag, rebalances, and DLQ count.
- JVM heap/GC/thread report.
- Start/end data counts and reconciliation result.

Until those artifacts are attached, the 10K–200K stages are **not tested**.
