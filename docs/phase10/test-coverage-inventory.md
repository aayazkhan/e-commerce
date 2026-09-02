# Phase 10 test and coverage inventory

Generated from the repository at 2026-08-20 after the Phase 10 shared-infrastructure test additions. Counts are intentionally mechanical and reproducible; they are not claims that a source file or declaration is fully tested.

## Scope

| Inventory item | Count | How it was counted |
| --- | ---: | --- |
| Deployable services | 25 | 24 named commerce services plus `api-gateway` in `settings.gradle.kts` |
| Shared modules | 8 | `backend/shared/*` included in Gradle |
| Gradle modules | 33 | All backend and shared includes |
| Kotlin production source files | 114 | `backend/**/src/main/kotlin/**/*.kt` |
| Kotlin test source files | 38 | `backend/**/src/test/kotlin/**/*.kt` |
| JUnit test declarations | 50 | `@Test` annotations in backend/shared Kotlin tests |
| Tagged integration test classes | 8 | `@Tag("integration")` |
| OpenAPI documents | 24 | `backend/**/openapi.yaml` |
| SQL migration files | 25 | `backend/**/src/main/resources/db/migration/*.sql` |

The source declaration scan reports approximately 966 top-level-looking declarations. It is only an audit hint: Kotlin compiler-generated serializers, lambdas, accessors, and private local functions mean it must not be used as a function-coverage denominator.

## Module inventory

| Module | Production Kotlin files | Test Kotlin files | Test declarations |
| --- | ---: | ---: | ---: |
| admin-service | 3 | 0 | 0 |
| analytics-service | 3 | 0 | 0 |
| api-gateway | 1 | 1 | 1 |
| audit-service | 3 | 0 | 0 |
| cart-service | 4 | 1 | 1 |
| catalog-service | 3 | 2 | 2 |
| category-service | 3 | 2 | 2 |
| checkout-service | 3 | 1 | 1 |
| cms-service | 4 | 1 | 1 |
| feature-flag-service | 3 | 1 | 1 |
| identity-service | 15 | 4 | 5 |
| inventory-service | 3 | 2 | 2 |
| media-service | 5 | 2 | 2 |
| notification-service | 3 | 1 | 1 |
| order-service | 3 | 1 | 2 |
| payment-service | 4 | 1 | 2 |
| pricing-service | 3 | 2 | 2 |
| promotion-service | 3 | 2 | 3 |
| recommendation-service | 3 | 1 | 1 |
| refund-service | 3 | 0 | 0 |
| review-service | 3 | 0 | 0 |
| search-service | 5 | 2 | 2 |
| seller-service | 3 | 1 | 1 |
| shipping-service | 4 | 1 | 2 |
| wishlist-service | 4 | 0 | 0 |
| shared/common | 4 | 2 | 3 |
| shared/database | 1 | 1 | 2 |
| shared/error-handling | 1 | 1 | 2 |
| shared/kafka | 2 | 1 | 2 |
| shared/observability | 1 | 1 | 1 |
| shared/redis | 2 | 1 | 2 |
| shared/security | 3 | 1 | 3 |
| shared/service-support | 6 | 1 | 1 |

## Important audit findings

- `admin-service`, `analytics-service`, `audit-service`, `refund-service`, `review-service`, and `wishlist-service` had no Kotlin test source before this phase.
- The current suite is primarily domain/configuration smoke coverage. Repository, route, Kafka, Redis, provider, and saga behavior are not covered at the requested depth.
- Eight tests are now explicitly classified as integration tests. Six migration tests remain environment-gated and the inventory/promotion concurrency tests require Testcontainers.
- No source, controller, repository, security, or generated serialization class is excluded from JaCoCo. This is deliberate; the report therefore exposes the real baseline.

## Reproducible audit commands

```bash
find backend shared -path '*/src/main/kotlin/*' -type f -name '*.kt' | wc -l
find backend shared -path '*/src/test/kotlin/*' -type f -name '*.kt' | wc -l
rg -n '@Test\b' backend shared -g '*.kt'
rg -l '@Tag\("integration"\)' backend shared -g '*.kt'
```
