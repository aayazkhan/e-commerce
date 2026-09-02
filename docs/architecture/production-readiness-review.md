# Production-readiness architecture review

Date: 2026-08-19  
Scope: backend phases 1–7, deployment manifests, shared runtime libraries, and operational documentation.  
Evidence level: source inspection and build/test evidence only unless a section says otherwise.

## Executive decision

The platform has a sound downstream-event direction for non-critical capabilities: order, payment, and inventory publish events; notification, review, recommendation, analytics, audit, and search consume them. The repository is **not yet evidence-backed as production-ready or as capable of 200K concurrent users**. A production gate remains open until environment-backed load, security, chaos, and restore tests are run.

## Findings

| Area | Current evidence | Risk | Required gate |
|---|---|---|---|
| Service ownership | Services own their own migration locations and repositories; orchestration uses HTTP/Kafka boundaries | Cross-service consistency can still be misunderstood as a distributed transaction | Keep checkout/order/payment/inventory invariants in their owning services; test saga reconciliation |
| Gateway | `backend/api-gateway` exposes health/metrics/CORS and is not a complete reverse proxy in this repository | Public traffic controls, routing, request limits, and edge authentication are not demonstrated | Deploy an actual gateway/ingress configuration and run route, auth, rate-limit, and size-limit tests |
| Database | Shared `ServiceDatabase` uses Hikari + Flyway; several services configure pool size 20–30 | Total connection demand can exceed PostgreSQL capacity as replicas scale | Capacity-test pool sizing against the real Postgres max connections; add indexes from query plans |
| Kafka/outbox | Critical writes publish through outbox; consumers manually commit and expose lag/failure counters | Lag, poison records, replay, and DLQ behavior require live broker evidence | Run broker outage, restart, replay, and DLQ drills; alert on consumer lag and outbox age |
| Redis | Cache failures are best-effort in shared cache; some readiness checks require Redis | Cache stampede/hot-key behavior and fail-open semantics are not measured | Load-test hot keys and invalidate/rebuild paths; define whether each Redis dependency is critical |
| Search/object storage | Search and media are separate boundaries; provider clients have bounded request timeouts in some paths | Reindex, object-store outage, and index lag can affect user-visible behavior | Test degraded search/media behavior and repair/reindex runbooks |
| Payment/shipping | Provider calls have bounded connect/request timeouts and provider webhooks | Retries and reconciliation must not duplicate financial actions | Test provider timeout, duplicate webhook, delayed webhook, and reconciliation paths |
| Observability | Health routes, request IDs, plain-text metrics, and Kafka lag counters exist in many services | Metrics are not yet proven scraped, aggregated, or alerted in a real cluster | Install dashboards/alerts and verify an end-to-end trace/request correlation |
| Kubernetes | Phase 7 manifest has replicas, probes, requests/limits, HPA, and PDBs | No demonstrated topology spread, network policy, image provenance, or cluster-autoscaler evidence | Apply hardened overlay and validate in the target cluster |
| Delivery | No repository CI workflow was present in the reviewed tree | A green local build is not a release control | Require build, tests, manifest validation, secret/dependency scanning, and signed immutable images |

## Dependency direction

Critical transaction path:

`client → gateway → checkout/order/payment/inventory`

Downstream path:

`critical service outbox → Kafka → notification/review/recommendation/analytics/audit/search consumers`

Consumer outages must therefore increase lag or DLQ volume, not make the producer transaction fail. No downstream consumer may call back into checkout or payment synchronously to complete a critical write.

## Review checklist

- [x] Service database access is encapsulated behind service repositories in the reviewed modules.
- [x] Cursor pagination has a hard maximum of 100 in shared common code.
- [x] Several critical services have graceful shutdown settings and readiness/liveness routes.
- [x] Outbox and consumer idempotency are present in the reviewed Phase 5–7 implementations.
- [ ] Public gateway routing and edge controls are complete and environment-tested.
- [ ] Live P95/P99, saturation, Kafka lag, and dependency latency baselines exist.
- [ ] A real database query-plan/index audit is complete.
- [ ] Kubernetes topology, network, image, and secret controls are validated in the target cluster.
- [ ] Backup restore, regional recovery, chaos, and progressive load results are attached.

## Release recommendation

Proceed only with a controlled staging validation. Production approval is blocked by the unchecked items above, especially the gateway, measured capacity, restore proof, and failure-injection evidence.
