# Dependency matrix

| Dependency | Consumers | Failure policy | Timeout/retry evidence | Owner/test gate |
|---|---|---|---|---|
| PostgreSQL | All stateful services | Critical for owning writes; readiness fails; no cross-service DB access | Hikari connection timeout; query/transaction budgets require live validation | DB/platform: failover, pool, restore |
| Kafka | Outbox publishers and downstream consumers | Critical writes must not synchronously depend on consumer availability | Producer/outbox behavior present; broker outage/replay not run | Messaging: outage, lag, replay, DLQ |
| Redis | Cart, flags, recommendations, selected caches | Per-service fallback; never source of truth for inventory/payment | Best-effort shared cache; stampede/failover not run | Platform: failover, hot key, eviction |
| OpenSearch | Search/reindex | Search degradation must not corrupt catalog source of truth | Search request timeout exists; outage/reindex not run | Search: reject/repair/reindex |
| Object storage | Media | Signed, bounded uploads; catalog metadata remains separate | Provider behavior requires environment test | Media: outage, restore, malware/size |
| Payment provider | Payment | Bounded request, explicit pending/failure, reconciliation | Connect/request timeout present; duplicate callback test not run | Payments: timeout/webhook/reconcile |
| Shipping provider | Shipping | Bounded request, explicit pending/failure, reconciliation | Connect/request timeout present; duplicate callback test not run | Shipping: timeout/callback |
| Notification providers | Notification | Asynchronous retry/DLQ; never block checkout | Timeout hardening added in Phase 8; outage test not run | Comms: provider outage/DLQ |
| OAuth/challenge providers | Identity | Explicit dependency-unavailable response | Timeout hardening added in Phase 8; outage test not run | Identity: timeout/rotation |

For each dependency, the release record must include owner, SLO, timeout, retry budget, circuit-breaker policy, credentials rotation date, and a tested rollback/fallback.
