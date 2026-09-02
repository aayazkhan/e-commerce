# Phase 10A uncovered-code inventory

## Latest authoritative inventory — 2026-08-24

Generated from `build/reports/jacoco/aggregate/jacoco.xml` after the clean test/report run:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,059 | 4,954 | 895 | 81.9338% |
| Branches | 6,289 | 8,644 | 2,355 | 72.7557% |
| Methods | 3,795 | 5,142 | 1,347 | 73.8040% |
| Classes | 881 | 1,126 | 245 | 78.2416% |

Tests: **631**; Kotlin test files: **164**. Unit tests and report generation: **PASS**. JaCoCo verification: **FAIL**. Phase 10A: **NO-GO**.

The current raw top 20 is dominated by application bootstrap classes and generated/compiler paths. The reachable branch inventory is maintained separately so route, state-machine, repository, security, and financial behavior are not hidden by line-only coverage.

## Superseding current measured inventory — 2026-08-24 (shared-boundary and identity-route batch)

Generated from `build/reports/jacoco/aggregate/jacoco.xml` after the forced aggregate rerun:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,055 | 4,954 | 899 | 81.8530% |
| Branches | 5,973 | 8,644 | 2,671 | 69.1000% |
| Methods | 3,723 | 5,142 | 1,419 | 72.4037% |
| Classes | 880 | 1,126 | 246 | 78.1528% |

Tests: **613**; Kotlin test files: **162**. Unit tests and report generation pass. JaCoCo verification remains **FAIL** in shared `common`, `error-handling`, and `kafka`; Phase 10A remains **NO-GO**. No exclusions or threshold changes were made.

Top-20 class inventory from the same XML:

| Rank | Class | Uncovered lines | Uncovered branches |
| ---: | --- | ---: | ---: |
| 1 | `com.ecommerce.media.ApplicationKt` | 10 | 23 |
| 2 | `com.ecommerce.search.ApplicationKt` | 10 | 22 |
| 3 | `com.ecommerce.cart.ApplicationKt` | 30 | 20 |
| 4 | `com.ecommerce.checkout.AddressWire` | 0 | 19 |
| 5 | `com.ecommerce.payment.ApplicationKt` | 26 | 18 |
| 6 | `com.ecommerce.order.ApplicationKt` | 25 | 18 |
| 7 | `com.ecommerce.wishlist.ApplicationKt` | 27 | 17 |
| 8 | `com.ecommerce.catalog.ApplicationKt` | 14 | 17 |
| 9 | `com.ecommerce.pricing.ApplicationKt` | 11 | 17 |
| 10 | `com.ecommerce.inventory.ApplicationKt` | 65 | 16 |
| 11 | `com.ecommerce.promotion.ApplicationKt` | 27 | 16 |
| 12 | `com.ecommerce.category.ApplicationKt` | 21 | 16 |
| 13 | `com.ecommerce.notification.ApplicationKt` | 20 | 16 |
| 14 | `com.ecommerce.seller.ApplicationKt` | 17 | 16 |
| 15 | `com.ecommerce.shipping.ApplicationKt` | 28 | 15 |
| 16 | `com.ecommerce.refund.ApplicationKt` | 27 | 15 |
| 17 | `com.ecommerce.checkout.ApplicationKt` | 8 | 15 |
| 18 | `com.ecommerce.search.SearchIndexClient` | 0 | 15 |
| 19 | `com.ecommerce.wishlist.WishlistEvent` | 0 | 14 |
| 20 | `com.ecommerce.platform.security.AccessClaims` | 2 | 13 |

## Superseding current measured inventory — 2026-08-24 (catalog contract batch)

Generated from `build/reports/jacoco/aggregate/jacoco.xml` after the forced aggregate rerun:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,968 | 8,644 | 2,676 | 69.0421% |
| Methods | 3,706 | 5,127 | 1,421 | 72.2840% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **606**; Kotlin test files: **160**. Unit tests and report generation pass. JaCoCo verification remains **FAIL**; Phase 10A remains **NO-GO**. The latest meaningful addition is the catalog `Product` sparse/default contract test, which covers two serializer branches while preserving the existing tests.

## Superseding latest measured inventory — 2026-08-24 (media/search/shared batch)

Generated from `build/reports/jacoco/aggregate/jacoco.xml` and its HTML report after the forced aggregate rerun:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,966 | 8,644 | 2,678 | 69.0190% |
| Instructions | 108,055 | 148,120 | 40,065 | 72.9510% |
| Methods | 3,705 | 5,127 | 1,422 | 72.2645% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **604**; Kotlin test files: **160**. Unit tests and report generation pass. JaCoCo verification remains **FAIL** because shared common/error-handling/kafka module rules still report uncovered branches and the aggregate is far below 100%. Phase 10A remains **NO-GO**.

The latest meaningful additions are ImageProcessor derivative/failure tests, a successful SearchConsumer worker lifecycle test, and contract-valid missing-required-field tests for shared serializers. The shared serializer tests did not change the synthetic branch counters, so those residuals remain recorded rather than covered with artificial constructor/reflection calls.

## Superseding latest measured inventory — 2026-08-24 (post-Catalog/Seller batch)

Generated from `build/reports/jacoco/aggregate/html/index.html` and `jacoco.xml` after the forced aggregate rerun:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,011 | 4,922 | 911 | 81.4913% |
| Branches | 5,962 | 8,644 | 2,682 | 68.9727% |
| Instructions | 107,827 | 148,118 | 40,291 | 72.7980% |
| Methods | 3,701 | 5,127 | 1,426 | 72.1865% |
| Classes | 875 | 1,123 | 248 | 77.9163% |

Tests: **598**; Kotlin test files: **159**. Unit tests and report generation pass. The unchanged hard JaCoCo verification gate remains FAIL; Phase 10A is **NO-GO**. The latest meaningful batch added Catalog duplicate/unexpected persistence assertions, Seller/Review malformed-event resilience, and Audit DLQ/default-actor assertions.

## Latest measured inventory — 2026-08-23

Generated from `build/reports/jacoco/aggregate/jacoco.csv` after the latest unit-test/report run:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,438 | 5,539 | 1,101 | 80.1228% |
| Branches | 5,945 | 8,636 | 2,691 | 68.8397% |
| Instructions | 107,729 | 148,077 | 40,348 | 72.7520% |
| Methods | 3,699 | 5,126 | 1,427 | 72.1615% |
| Classes | 874 | 1,123 | 249 | 77.8272% |

Tests: **597**; Kotlin test files: **159**. Unit tests and report generation pass. The unchanged hard JaCoCo verification gate remains FAIL; Phase 10A is **NO-GO**. The latest meaningful additions covered the CMS `APPROVED` transition arm, feature-flag whitespace/malformed-version targeting, promotion nullable percentage-rate branches, and Checkout persisted-state compensation behavior.

## Fresh clean inventory — 2026-08-23

Generated from the regenerated aggregate JaCoCo CSV/XML after `clean test` and `jacocoTestReport`:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,410 | 5,512 | 1,102 | 80.0073% |
| Branches | 5,909 | 8,638 | 2,729 | 68.4070% |
| Instructions | 107,405 | 148,033 | 40,628 | 72.5548% |
| Methods | 3,692 | 5,119 | 1,427 | 72.1235% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

The largest measured branch concentrations are checkout/identity (209 each), promotion (170), admin (148), seller (141), catalog (121), notification (115), order (114), cart/cms (113 each), and inventory (102). The exact class-level top list is recorded in `coverage-heatmap.md`; generated Ktor application wiring and serialization classes are kept visible rather than excluded.

## Superseding fresh inventory — 2026-08-23

Generated from the latest `build/reports/jacoco/aggregate/jacoco.xml` and CSV after clean tests/report:

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,979 | 4,892 | 913 | 81.3369% |
| Branches | 5,907 | 8,638 | 2,731 | 68.3839% |
| Instructions | 107,360 | 148,033 | 40,673 | 72.5244% |
| Methods | 3,691 | 5,119 | 1,428 | 72.1039% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

Declared tests: 575; Kotlin test files: 157. The aggregate test/report pipeline passed; `jacocoTestCoverageVerification` failed on unchanged 100% rules, and `clean build` passed. No exclusions or threshold changes were made.

Generated from `build/reports/jacoco/aggregate/jacoco.xml` on 2026-08-23. The aggregate XML/HTML report remains authoritative.

## Aggregate

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,972 | 4,892 | 920 | 81.1938% |
| Branches | 5,872 | 8,638 | 2,766 | 67.9787% |
| Instructions | 106,937 | 148,033 | 41,096 | 72.2358% |
| Methods | 3,687 | 5,119 | 1,432 | 72.0258% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

## Top 20 production classes/functions

| Rank | Service/package | Class/function | Missed lines | Missed branches | Missing behavior |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | notification | `ApplicationKt$module$1.invokeSuspend` | 4 | 25 | consumer delivery callback outcomes |
| 2 | admin | `ApplicationKt$module$1.invokeSuspend` | 1 | 25 | downstream orchestration outcomes |
| 3 | media | `ApplicationKt` | 10 | 23 | route/configuration/authorization |
| 4 | search | `ApplicationKt` | 10 | 22 | route/configuration alternatives |
| 5 | cart | `ApplicationKt` | 30 | 20 | route/configuration guards |
| 6 | inventory | `ApplicationKt` | 65 | 18 | route/configuration guards |
| 7 | payment | `ApplicationKt` | 26 | 18 | route/configuration guards |
| 8 | order | `ApplicationKt` | 25 | 18 | route/configuration guards |
| 9 | seller | `ApplicationKt` | 17 | 18 | ownership/downstream guards |
| 10 | wishlist | `ApplicationKt` | 27 | 17 | route/configuration guards |
| 11 | catalog | `ApplicationKt` | 14 | 17 | cache/permission/internal guards |
| 12 | pricing | `ApplicationKt` | 11 | 17 | route/configuration guards |
| 13 | promotion | `ApplicationKt` | 27 | 16 | route/configuration guards |
| 14 | category | `ApplicationKt` | 21 | 16 | route/configuration guards |
| 15 | shipping | `ApplicationKt` | 28 | 15 | route/configuration/provider guards |
| 16 | refund | `ApplicationKt` | 27 | 15 | route/configuration/provider guards |
| 17 | notification | `ApplicationKt` | 19 | 15 | route/configuration/provider guards |
| 18 | checkout | `ApplicationKt` | 8 | 15 | route/configuration/saga wiring |

## Reachable business code still open

| Package | Class | Method | Missed branches | Test state required |
| --- | --- | --- | ---: | --- |
| promotion | `PromotionRepository` | `calculate` | 6 | remaining eligibility, short-circuit, and discount boundaries |
| identity/application | `IdentityService` | `register` | 4 | contact/name/password validation combinations |
| identity/infrastructure | `IdentityRepository` | `rotateRefreshToken` | 1 | valid versus missing/expired/reused token result |
| inventory | `InventoryRepository` | `maybeLowStock` | 3 | threshold and nullable stock outcomes |
| inventory | `InventoryRepository` | `transition$lambda$0` | 5 | valid/invalid reservation state transitions |
| seller | `SellerRepository` | `apply$lambda$0` | 5 | deduplication and malformed event outcomes |
| shipping | `HttpShippingProvider` | response mapping | remaining | missing identifiers, optional values, status and transport outcomes |

## Documentation of exclusions

No application production packages, classes, methods, lines, or branches are excluded from JaCoCo. Generated/private serializer markers and startup wiring are listed as open until their reachability is resolved; they are not treated as covered by exclusion.

## Fresh authoritative result (2026-08-25)

The aggregate XML reports **889 missed lines**, **2,319 missed branches**, **1,345 missed methods**, and **244 missed classes** out of 4,953 lines, 8,636 branches, 5,142 methods, and 1,126 classes. The largest package gaps are promotion (133 branches), admin (128), seller (126), checkout (115), identity HTTP (114), cart (106), order (100), CMS (99), and payment (98).

Current result: **889 uncovered lines and 2,319 uncovered branches; Phase 10A NO-GO**.

## Final verification snapshot (2026-08-25)

`./gradlew jacocoTestCoverageVerification --no-daemon --console=plain` returned failure for `shared:common`, `shared:error-handling`, and `shared:kafka`. Their remaining misses are the generated serialization constructor-mask branches and compiler-generated coroutine entry branch listed in `remaining-branch-inventory.md`; no code was excluded.
