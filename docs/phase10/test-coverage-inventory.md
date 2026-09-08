# Phase 10 test and coverage inventory

**Regenerated 2026-09-08** using this document's own reproducible audit commands (below), replacing
the 2026-08-20 snapshot. The original numbers were stale enough to be actively misleading — e.g. it
claimed `review-service`, `refund-service`, and `admin-service` had zero test files, when each
already had several; see [final-test-report.md](final-test-report.md)'s "Critical-path and
infrastructure status" table for the fuller correction. Counts are mechanical and reproducible; they
are not claims that a source file or declaration is fully tested.

## Scope

| Inventory item | Count | How it was counted |
| --- | ---: | --- |
| Deployable services | 25 | 24 named commerce services plus `api-gateway` in `settings.gradle.kts` |
| Shared modules | 8 | `backend/shared/*` included in Gradle |
| Gradle modules | 33 | All backend and shared includes (KMP frontend modules under `shared/core/*` and `apps/sellerApp` are tracked separately, not in this backend-only inventory) |
| Kotlin production source files | 117 | `backend/**/src/main/kotlin/**/*.kt` + `shared/**/src/main/kotlin/**/*.kt` |
| Kotlin test source files | 196 | `backend/**/src/test/kotlin/**/*.kt` + `shared/**/src/test/kotlin/**/*.kt` |
| JUnit `@Test` declarations | 711 | `@Test` annotations in backend/shared Kotlin tests |
| Tagged integration test classes | 33 | `@Tag("integration")` — includes the module-boot-wiring tests added this phase (one per service) plus targeted ones like the Kafka redelivery test |
| OpenAPI documents | 24 | `backend/**/openapi.yaml` |
| SQL migration files | 25 | `backend/**/src/main/resources/db/migration/*.sql` |

## Module inventory

| Module | Production Kotlin files | Test Kotlin files | Test declarations |
| --- | ---: | ---: | ---: |
| admin-service | 3 | 6 | 19 |
| analytics-service | 3 | 5 | 11 |
| api-gateway | 3 | 2 | 19 |
| audit-service | 3 | 3 | 10 |
| cart-service | 4 | 7 | 28 |
| catalog-service | 3 | 8 | 29 |
| category-service | 3 | 8 | 18 |
| checkout-service | 3 | 8 | 47 |
| cms-service | 4 | 6 | 20 |
| feature-flag-service | 3 | 5 | 15 |
| identity-service | 16 | 16 | 69 |
| inventory-service | 3 | 8 | 36 |
| media-service | 5 | 9 | 19 |
| notification-service | 3 | 8 | 29 |
| order-service | 3 | 7 | 28 |
| payment-service | 4 | 8 | 23 |
| pricing-service | 3 | 7 | 22 |
| promotion-service | 3 | 10 | 47 |
| recommendation-service | 3 | 5 | 13 |
| refund-service | 3 | 5 | 13 |
| review-service | 3 | 5 | 15 |
| search-service | 5 | 7 | 32 |
| seller-service | 3 | 5 | 14 |
| shipping-service | 4 | 8 | 18 |
| wishlist-service | 4 | 6 | 13 |
| shared/common | 4 | 5 | 21 |
| shared/database | 1 | 1 | 2 |
| shared/error-handling | 1 | 2 | 11 |
| shared/kafka | 2 | 4 | 23 |
| shared/observability | 1 | 1 | 1 |
| shared/redis | 2 | 2 | 4 |
| shared/security | 3 | 3 | 14 |
| shared/service-support | 6 | 6 | 21 |

## Important audit findings (corrected)

- Every backend/shared module has at least one test file now; none are at zero. The 2026-08-20
  snapshot's "no Kotlin test source" claim for six services was wrong even at the time — those
  services already had real repository/route/domain test files, they just weren't counted correctly.
- Aggregate JaCoCo coverage is 83.2% line / 73.3% branch (see [final-test-report.md](final-test-report.md)
  and [coverage-exceptions.md](coverage-exceptions.md) for the branch-tolerance policy and the
  module-wiring integration-test policy that closes the remaining line gap once Docker is available).
- Every service now has a `<Service>ModuleBootIntegrationTest.kt` (`@Tag("integration")`) that boots
  the real `Application.module()` — config parsing, live DB connection + Flyway migration, Kafka
  producer/consumer construction, JWT verifier construction, routing — against Testcontainers. These
  are structurally verified (compile, correctly reach a "no Docker" stopping point in an environment
  without one) but not yet run to a real pass/fail result anywhere in this repo's history — that needs
  Docker, which was unavailable in every workspace this document has been written from so far.
- No source, controller, repository, security, or generated serialization class is excluded from
  JaCoCo. This remains deliberate; the branch-coverage tolerances in `toleratedMissedBranches` are the
  one documented, narrow exception, and only for confirmed compiler-generated branches (see
  [coverage-exceptions.md](coverage-exceptions.md)).

## Reproducible audit commands

```bash
find backend shared -path '*/src/main/kotlin/*' -type f -name '*.kt' | wc -l
find backend shared -path '*/src/test/kotlin/*' -type f -name '*.kt' | wc -l
grep -rn '@Test\b' backend shared --include='*.kt' | wc -l
grep -rl '@Tag("integration")' backend shared --include='*.kt' | wc -l
```
