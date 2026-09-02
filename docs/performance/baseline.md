# Performance baseline

Status: **NOT TESTED in a production-like environment**  
Last reviewed: 2026-08-19

No defensible P50/P95/P99, RPS, CPU, memory, GC, database, Redis, Kafka, OpenSearch, or network baseline was present in the repository. Do not infer performance from a successful local build.

## Baseline capture protocol

Run each scenario for 15 minutes after a 5-minute warm-up, with three repetitions and a clean dataset snapshot. Capture:

- HTTP: request count, P50/P95/P99, status/error rate, timeouts, saturation.
- JVM: heap, non-heap, GC pause/count, live threads, process CPU.
- PostgreSQL: query latency by route, active/idle/waiting connections, locks, slow queries, buffer/cache hit ratio.
- Redis: command latency, ops/sec, hit ratio, evictions, memory, hot keys.
- Kafka: records/sec, producer errors, consumer lag/max lag, rebalance count, DLQ count, outbox age.
- OpenSearch: request latency, rejected tasks, heap, shard health, refresh/indexing lag.
- Node/network: CPU throttling, memory pressure, disk, network ingress/egress.

## Route matrix

| Route | Class | Target to validate | Current result |
|---|---|---:|---|
| `GET /api/v1/catalog/products` | read/list | P95 ≤ 300 ms | NOT TESTED |
| `GET /api/v1/search` | read/search | P95 ≤ 400 ms | NOT TESTED |
| `GET /api/v1/cart` | read/session | P95 ≤ 250 ms | NOT TESTED |
| `POST /api/v1/cart/items` | write/session | P95 ≤ 400 ms | NOT TESTED |
| `POST /api/v1/checkout` | critical write | P95 ≤ 800 ms excluding async work | NOT TESTED |
| `POST /api/v1/orders` | critical write | P95 ≤ 800 ms | NOT TESTED |
| `GET /api/v1/orders/{id}` | read/detail | P95 ≤ 300 ms | NOT TESTED |
| `POST /api/v1/payments` | provider boundary | P95 ≤ 1,500 ms or explicit async state | NOT TESTED |
| `GET /api/v1/shipments/{id}` | provider/read | P95 ≤ 500 ms | NOT TESTED |

Targets are acceptance targets for the test plan, not claims about current behavior. A route that depends on an external provider must report provider latency separately.

## Comparison rule

After each change, compare the same dataset, load shape, replica count, and dependency health. Reject a change that improves median while violating P99, error budget, connection saturation, or Kafka lag.
