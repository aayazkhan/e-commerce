# Commerce Platform

Production-grade Kotlin Multiplatform commerce monorepo for customer, admin, and seller experiences.

## Current status

Backend implementation phases 1–7 and Phase 8 hardening are present, with Phase 9 release/deployment scaffolding and Phase 10 test/coverage infrastructure now added. Controlled production readiness is not claimed: the 100% coverage gate and live capacity, security, chaos, restore, cluster, provider, and go-live evidence remain incomplete or environment-dependent and are tracked in the [Phase 10 report](docs/phase10/final-test-report.md) and [Phase 9 report](docs/production-readiness/phase9-go-live-report.md).

## Architecture at a glance

```text
Android / iOS / SEO web storefront / Admin / Seller
                         |
                  CDN + WAF + Gateway
                         |
       KMP shared contracts, rules, sync, and clients
                         |
      Kotlin services with one database owner per service
                         |
 PostgreSQL | Redis | OpenSearch | Object storage | Kafka
```

The system is API-first, event-driven, offline-first on supported client features, and secure by default. Financial, inventory, promotion, and order decisions are authoritative on the server.

## Read first

- [ARCHITECTURE.md](ARCHITECTURE.md) — system, module, service, and deployment decisions
- [API.md](API.md) — REST conventions, resources, idempotency, and versioning
- [DATABASE.md](DATABASE.md) — ownership, consistency, migrations, and retention
- [SECURITY.md](SECURITY.md) — trust boundaries and controls
- [DEPLOYMENT.md](DEPLOYMENT.md) — environments, Kubernetes topology, and delivery
- [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md) — local prerequisites and workflow
- [TESTING.md](TESTING.md) — test strategy and quality gates
- [OBSERVABILITY.md](OBSERVABILITY.md) — logs, metrics, traces, and alerts
- [High-concurrency backend upgrade](docs/SCALABILITY_UPGRADE.md) — capacity model, scaling controls, failure strategy, and migration gates
- [Frontend and mobile scalability upgrade](docs/FRONTEND_SCALABILITY_UPGRADE.md) — KMP, caching, offline sync, web/PWA, mobile performance, and release strategy
- [Backend implementation status](backend/README.md) — current buildable foundation and remaining service phases
- [CONTRIBUTING.md](CONTRIBUTING.md) — dependency rules and contribution workflow

## Delivery phases

1. Architecture baseline — this delivery
2. Foundation — build convention, shared primitives, API contracts, auth foundation, persistence, telemetry
3. Product discovery — category, catalog, pricing, media, and search
4. Core commerce — cart, checkout, payment, order, and inventory
5. Growth — reviews, promotions, loyalty, notifications, CMS, analytics, and recommendations
6. Operations — admin and seller workflows
7. Platform — PWA/offline sync hardening, SEO, Kubernetes, CI/CD, disaster recovery, and production readiness

No service should be treated as production-ready until its API, migrations, authorization, observability, tests, runbook, and failure behavior are present.
