# SLO/SLI and error-budget policy

Status: **TARGETS DEFINED — telemetry wiring and historical compliance pending**

| SLI | Initial target | Measurement |
|---|---:|---|
| Public availability, non-5xx/non-timeout | 99.9% monthly | Gateway request counter by route/status |
| Critical write availability | 99.95% monthly | Checkout/order/payment accepted or explicit safe retry |
| Catalog/search P95 | ≤ 400 ms | Gateway histogram excluding client disconnects |
| Checkout/order P95 | ≤ 800 ms | End-to-end request histogram |
| Payment provider dependency success | ≥ 99.5% | Provider result and timeout counters |
| Consumer freshness | 99% under 60 s lag | Kafka consumer lag/freshness metric |
| Notification eventual delivery | 99% within 15 min | Delivery status timestamps, excluding opted-out users |
| Backup restore | Target only | Timed restore exercise |

## Policy

- Burn-rate alerts page the owning team; warning alerts create a ticket.
- Freeze risky feature releases when the critical-write error budget is exhausted.
- Kafka consumer lag is an availability/freshness signal for downstream services, not a reason to fail an order transaction.
- Every SLI dashboard must expose service, route, status, dependency, region, and version labels without raw PII.

These are proposed starting targets. They become contractual only after owners, measurement windows, and customer-impact definitions are approved.
