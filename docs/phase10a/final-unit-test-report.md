# Phase 10A unit-test coverage report

## Latest authoritative checkpoint — 2026-08-24

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,059 | 4,954 | 895 | 81.9338% |
| Branches | 6,289 | 8,644 | 2,355 | 72.7557% |
| Methods | 3,795 | 5,142 | 1,347 | 73.8040% |
| Classes | 881 | 1,126 | 245 | 78.2416% |

Tests: **631**; Kotlin test files: **164**. Unit tests/report: **PASS**. JaCoCo verification: **FAIL**. Clean build: **PASS**. OpenAPI baseline: **24 PASS**. Production defects fixed in this continuation: **0**. Regression/contract tests added: **8** (including four shared model-copy assertions that did not alter JaCoCo counters). Artificial coverage techniques: **NONE**. Coverage exclusions: **NONE**. Phase 10A: **NO-GO**.

The latest hard-gate run failed in shared `error-handling`, `common`, and `kafka` branch rules (each reports 0.9 versus required 1.0), in addition to the aggregate report remaining below 100%.

## Current authoritative checkpoint — 2026-08-24 (shared-boundary and identity-route batch)

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,055 | 4,954 | 899 | 81.8530% |
| Branches | 5,973 | 8,644 | 2,671 | 69.1000% |
| Methods | 3,723 | 5,142 | 1,419 | 72.4037% |
| Classes | 880 | 1,126 | 246 | 78.1528% |

Tests: **613**; Kotlin test files: **162**. Unit tests/report: **PASS**. JaCoCo verification: **FAIL** in shared `common`, `error-handling`, and `kafka`, each because the hard branch ratio remains below 1.0. Production defects fixed in this batch: **0**; regression/boundary tests added: **5**. Artificial coverage techniques: **NONE**. Coverage exclusions: **NONE**. Phase 10A: **NO-GO**.

## Current authoritative checkpoint — 2026-08-24 (catalog contract batch)

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,968 | 8,644 | 2,676 | 69.0421% |
| Methods | 3,706 | 5,127 | 1,421 | 72.2840% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **606**; Kotlin test files: **160**. Unit tests/report: **PASS**. JaCoCo verification: **FAIL**. The catalog sparse/default `Product` serialization regression test covers two generated branches without reflection or exclusions. Phase 10A: **NO-GO**.

## Current authoritative checkpoint — 2026-08-24 (media/search/shared batch)

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,966 | 8,644 | 2,678 | 69.0190% |
| Instructions | 108,055 | 148,120 | 40,065 | 72.9510% |
| Methods | 3,705 | 5,127 | 1,422 | 72.2645% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **604**; Kotlin test files: **160**. `./gradlew test jacocoTestReport --rerun-tasks` and targeted media/search/shared tests: **PASS**. `jacocoTestCoverageVerification`: **FAIL** (shared common/error-handling/kafka branch rules and aggregate coverage remain below 100%). No exclusions or threshold changes were made. Phase 10A: **NO-GO**.

Production/testability change: `ImageProcessor` now depends on the existing `MediaBlobStore` abstraction and a narrow `MediaProcessingStore` seam; `MediaStorage`/`MediaRepository` remain the production implementations. Regression tests cover successful derivative creation and failure-state transitions. Search worker coverage now includes successful poll processing before cancellation. No production behavior was weakened.

## Current authoritative checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,011 | 4,922 | 911 | 81.4913% |
| Branches | 5,962 | 8,644 | 2,682 | 68.9727% |
| Instructions | 107,827 | 148,118 | 40,291 | 72.7980% |
| Methods | 3,701 | 5,127 | 1,426 | 72.1865% |
| Classes | 875 | 1,123 | 248 | 77.9163% |

Tests: **598**; Kotlin test files: **159**. Unit tests and aggregate JaCoCo report: **PASS**. JaCoCo verification: **FAIL** because the existing hard 100% line/branch rules remain unmet. Phase 10A: **NO-GO**. No exclusions or threshold changes were made.

## Latest measured checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,011 | 4,922 | 911 | 81.4913% |
| Branches | 5,947 | 8,636 | 2,689 | 68.8629% |
| Instructions | 107,754 | 148,100 | 40,346 | 72.7576% |
| Methods | 3,700 | 5,127 | 1,427 | 72.1670% |
| Classes | 874 | 1,123 | 249 | 77.8272% |

Tests: **598**; Kotlin test files: **159**. Unit tests: **PASS**. JaCoCo report: **PASS**. JaCoCo verification: **FAIL** because the existing hard 100% line/branch rules are not met. Phase 10A: **NO-GO**.

Latest production/testability change: `PromotionRepository` now accepts an optional `Clock` while retaining `Clock.systemUTC()` as the production default. Regression tests assert start-inclusive and end-exclusive promotion windows at exact deterministic boundaries. No coverage exclusions or threshold changes were made.

## Latest measured checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,438 | 5,539 | 1,101 | 80.1228% |
| Branches | 5,945 | 8,636 | 2,691 | 68.8397% |
| Instructions | 107,729 | 148,077 | 40,348 | 72.7520% |
| Methods | 3,699 | 5,126 | 1,427 | 72.1615% |
| Classes | 874 | 1,123 | 249 | 77.8272% |

Tests: **597**; Kotlin test files: **159**. Unit tests: **PASS**. JaCoCo report: **PASS**. The unchanged JaCoCo verification gate: **FAIL** because 100% line/branch coverage has not been reached. Phase 10A: **NO-GO**.

Latest meaningful tests include Checkout generic compensation recovery, CMS invalid transitions from `APPROVED`, feature-flag whitespace and malformed-version targeting, and promotion nullable-rate calculation. The shipping provider malformed `amountMinor` handling remains a real production defect fix with regression coverage. No coverage exclusions or threshold changes were made.

## Fresh clean checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,410 | 5,512 | 1,102 | 80.0073% |
| Branches | 5,909 | 8,638 | 2,729 | 68.4070% |
| Instructions | 107,405 | 148,033 | 40,628 | 72.5548% |
| Methods | 3,692 | 5,119 | 1,427 | 72.1235% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

Tests: **582**; Kotlin test files: **157**. Unit tests and build pass; JaCoCo report generation passes, but the hard verification gate fails. Phase 10A is **NO-GO**. This checkpoint includes the meaningful Kafka shutdown-failure regression test; no production code changed in this batch.

## Superseding final-batch measurement — 2026-08-23

This is the latest measured checkpoint; Phase 10A is not complete.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,979 | 4,892 | 913 | 81.3369% |
| Branches | 5,907 | 8,638 | 2,731 | 68.3839% |
| Instructions | 107,360 | 148,033 | 40,673 | 72.5244% |
| Methods | 3,691 | 5,119 | 1,428 | 72.1039% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

Tests: **575** across **157** Kotlin test files. Clean unit tests/report: **PASS**. Clean build: **PASS**. JaCoCo verification: **FAIL** (the existing hard 100% module rules report unmet branch ratios, including `shared:common` and `shared:error-handling`). OpenAPI baseline: **24 PASS**. Phase 10A: **NO-GO**.

Latest meaningful coverage work: Cart merge-cap behavior, feature-flag missing-target and outbox paths, refund outbox publication, CMS missing-page/version/scheduling paths, and recommendation cold-start/invalid-timestamp paths. Test-double corrections were made only where they failed to model the production SQL predicate/update; no production behavior or coverage configuration was weakened.

Date: 2026-08-23

## Current verdict

**NO-GO.** Unit tests and aggregate report generation pass, but the unchanged hard JaCoCo line/branch gate remains below 100%.

## Measured coverage

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Line | 3,972 | 4,892 | 920 | 81.1938% |
| Branch | 5,872 | 8,638 | 2,766 | 67.9787% |
| Instruction | 106,937 | 148,033 | 41,096 | 72.2358% |
| Function/method | 3,687 | 5,119 | 1,432 | 72.0258% |
| Class | 873 | 1,122 | 249 | 77.8075% |

## Progress from the current user baseline

| Measure | Baseline | Current |
| --- | ---: | ---: |
| Line coverage | 79.8647% | 81.1938% |
| Branch coverage | 65.2476% | 67.9787% |
| Declared tests | 531 | 572 |
| Kotlin test files | 154 | 157 |
| Uncovered lines | 982 | 920 |
| Uncovered branches | 3,004 | 2,766 |

## Latest meaningful batches

- SearchIndexClient status/parser tests; Checkout sparse wire/client payload tests; Audit optional-filter/malformed JSON tests.
- Category lifecycle conflict/path tests; SearchConsumer worker recovery tests; Promotion route/default serialization tests; Catalog cache/permission guard tests.
- Payment refund/webhook state tests covering pending/partial/failed provider outcomes, idempotency, over-refund, ownership/currency guards, and missing provider references.
- RedisCache command-boundary tests for success/failure/TTL behavior, privileged-role notification authorization, PricingRepository sale/end-date persistence, update/delete, and outbox paths, direct seller lifecycle transition coverage, and shipping provider response-validation coverage.
- Search transport/status-boundary and route query/reindex tests, Inventory privileged-owner access, Review malformed/DLQ payload cases, and a behavior-preserving Promotion redemption helper cleanup.

No production exclusions, threshold changes, disabled rules, reflection-only coverage, class-under-test mocks, or test rewrites were added.

## Validation status

| Check | Result |
| --- | --- |
| Unit tests and aggregate report | PASS |
| `./gradlew clean test jacocoTestReport --no-daemon --console=plain` | PASS |
| `./gradlew jacocoTestCoverageVerification --no-daemon --console=plain` | FAIL — shared common, Kafka, and error-handling module rules still report below 100%; aggregate production counters also remain below 100% |
| `./gradlew build --no-daemon --console=plain` | PASS |
| OpenAPI contracts | PASS — 24 contracts parsed |

## Current uncovered inventory

The latest JaCoCo report contains **920 uncovered lines** and **2,766 uncovered branches**. The hard 100% line/branch verification gate therefore remains **FAIL**, and Phase 10A remains **NO-GO**.
| `./gradlew jacocoTestCoverageVerification --no-daemon --console=plain` | FAIL — expected; hard 100% gate remains enabled |

## Remaining work

The largest measured service gaps are Identity (283 branches), Checkout (236), Promotion (171), Admin (153), Seller (152), Catalog (127), Notification (122), Inventory (117), Cart (117), Media (112), Search (111), Payment (108), and Pricing (108). See [`coverage-heatmap.md`](coverage-heatmap.md), [`top-coverage-gaps.md`](top-coverage-gaps.md), and [`remaining-branch-inventory.md`](remaining-branch-inventory.md).

Phase 10A remains **NO-GO** until `jacocoTestCoverageVerification` passes with exactly zero missed lines and zero missed branches.

## Latest authoritative rerun (2026-08-25)

| Metric | Result |
| --- | ---: |
| Lines | 4,064 / 4,953 covered (82.0109%) |
| Missed lines | 889 |
| Branches | 6,317 / 8,636 covered (73.1478%) |
| Missed branches | 2,319 |
| Methods | 3,797 / 5,142 covered |
| Classes | 882 / 1,126 covered |
| JUnit XML test cases | 637 |
| Kotlin/Java test source files | 167 |
| `./gradlew test jacocoTestReport --rerun-tasks` | PASS |
| `./gradlew jacocoTestCoverageVerification` | FAIL — shared common/error-handling gate remains below 100% |

Production defects fixed in this continuation: one Recommendation malformed-array null-safety defect, with regression coverage; one Review ingestion no-op/dead parsing cleanup. Coverage exclusions: **NONE**. Artificial coverage techniques: **NONE**. Phase 10A remains **NO-GO**.

The final post-build verification was re-run after regenerating the report and returned **FAIL**. Remaining module gate misses are `shared:common` 3, `shared:error-handling` 2, and `shared:kafka` 2; these are documented generated/continuation branches, not excluded code.
