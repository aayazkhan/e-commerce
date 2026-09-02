# Capacity model

Status: **MODEL ONLY — assumptions are placeholders until production-like telemetry is supplied**  
Last reviewed: 2026-08-19

This model prevents the common mistake of treating 200K registered users as 200K concurrent users. Replace every `A` value with measured traffic before using the result for provisioning.

## Assumptions

| Variable | Symbol | Initial planning value | Meaning |
|---|---:|---:|---|
| Registered users | U | 200,000 | Account count, not concurrency |
| Daily active users | DAU | 60,000 | Must come from analytics |
| Peak concurrent users | C | 6,000 | Scenario assumption: 10% of DAU |
| Requests per active user per minute | Rm | 6 | Browse/search/cart mix |
| Peak multiplier | P | 2.0 | Peak minute vs daily average |
| Read/write ratio | rw | 90/10 | Must be measured by route |
| Checkout share of requests | co | 1% | Must be measured |
| Orders per checkout | oc | 1 | Includes retries only once by idempotency key |
| Kafka events per order | eo | 12 | Replace with event census |
| Average event size | S | 2 KiB | Compressed broker payload differs |
| Average API response size | B | 40 KiB | Includes JSON only |

## Formulas

```text
Peak RPS = DAU × (Rm / 60) × P / 60
Peak concurrent sessions = C
Read RPS = Peak RPS × rw.read
Write RPS = Peak RPS × rw.write
Checkout RPS = Peak RPS × co
Orders/minute = Checkout RPS × oc × 60
Kafka events/second = Orders/second × eo + non-order events/second
Kafka ingress = Kafka events/second × S
DB logical QPS = route-specific read/write amplification × Peak RPS
Redis QPS = cache hit/miss operations per request × Peak RPS
Egress bandwidth = Peak RPS × B × 8
Monthly raw event storage = events/second × S × 86,400 × retention days
```

With the placeholder values, `Peak RPS = 120 RPS`, `Peak concurrent sessions = 6,000`, and checkout traffic is about `1.2 RPS`. These are planning numbers, not measurements and not a 200K-user capacity claim.

## Required sizing exercise

Run the model for at least:

1. Normal peak: 2× observed hourly average.
2. Flash sale: 10× normal browse traffic, 5× hot-product writes.
3. Coupon launch: concentrated promotion reads and redemption contention.
4. Recovery: one service replica and one broker unavailable.

Record per scenario: service replicas, CPU/memory, JVM GC, HTTP P50/P95/P99, PostgreSQL active/waiting connections, Redis ops/latency, Kafka records/sec and lag, OpenSearch latency, and error rate.
