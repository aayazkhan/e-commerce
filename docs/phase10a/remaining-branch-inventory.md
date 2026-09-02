# Phase 10A remaining branch inventory

## Latest authoritative inventory — 2026-08-24

Generated from `build/reports/jacoco/aggregate/jacoco.xml` after the clean test/report run. **895 lines** and **2,355 branches** remain uncovered; the hard gate remains **FAIL**, so Phase 10A is **NO-GO**.

| Priority | Service | Class/function | Missed lines | Missed branches | Missing path / next action |
| --- | --- | --- | ---: | ---: | --- |
| P0 | promotion | `ApplicationKt` | 27 | 16 | infrastructure-backed bootstrap and health alternatives |
| P0 | checkout | `ApplicationKt` / Saga | 8 | 15 | startup wiring plus remaining Saga state/error branches |
| P0 | seller | `ApplicationKt` | 17 | 16 | startup wiring and seller lifecycle routes |
| P0 | admin | `ApplicationKt` | 12 | 9 | proxy/bulk-job startup branches |
| P0 | identity.http | `Routes.kt` | 0 | 86 | optional headers, auth/permission guards, and parameter branches |
| P0 | inventory | `ApplicationKt` | 65 | 16 | inventory startup and health/error paths |
| P0 | payment | `ApplicationKt` | 26 | 18 | payment startup and route/state wiring |
| P1 | search | `SearchConsumer.apply` | 0 | 10 | semantic event arms are covered; inspect remaining dispatch edges |
| P1 | search | `SearchIndexClient` | 0 | 15 | request/response adapter decisions |
| P1 | review | `ReviewRepository` | 0 | 9 | ingestion/moderation nullable and error outcomes |
| P1 | cms | `CmsRepository` | 0 | 7 | persistence lifecycle outcomes |
| P1 | flags | `FlagRepository` | 0 | 5 | targeting/validation outcomes |
| P2 | shared | `common`, `error-handling`, `kafka` | 0 | 8 | existing module gates still fail on generated/coroutine edges |

The remaining inventory is branch-first: each row must be mapped to a production condition and behavioral input before another test is added. No exclusion, threshold change, or reflection-only test is used.

## Superseding current measured inventory — 2026-08-24 (shared-boundary and identity-route batch)

Current aggregate from `build/reports/jacoco/aggregate/jacoco.xml`: **4,055/4,954 lines (81.8530%)**, **5,973/8,644 branches (69.1000%)**. Remaining: **899 lines** and **2,671 branches**. Tests: **613** in **162** Kotlin test files. Phase 10A remains **NO-GO**.

| Priority | Service | Class/function | Missed lines | Missed branches | Missing path / next action |
| --- | --- | --- | ---: | ---: | --- |
| P0 | checkout | `ApplicationKt` / Saga | 8 | 15 | orchestration and compensation branches |
| P0 | promotion | `PromotionRepository.calculate` | 0 | 4 | remaining eligibility/calculation combinations |
| P0 | seller | `SellerRepository.apply` | 0 | 3 | event payload fallbacks |
| P0 | identity | `IdentityService.register` | 0 | 4 | remaining validation short-circuit combinations |
| P0 | identity.http | route handlers/guards | 0 | 125 | bootstrap/route-generated paths plus parameter guard |
| P1 | search | `SearchConsumer.apply` | 0 | 10 | compiler-generated string-switch edges remain after all actual event types |
| P1 | review | `ReviewRepository.accept/moderate` | 0 | 8 | malformed event and lifecycle combinations |
| P1 | media | `ApplicationKt` | 10 | 23 | module/bootstrap branches |
| P2 | shared common | serializer constructors | 0 | 3 | generated default-mask branches |
| P2 | shared error-handling | serializer constructors | 0 | 2 | generated default-mask branches |
| P2 | shared kafka | envelope/worker generated edges | 0 | 3 | serializer/coroutine branches |

The shared hard gate still fails only in `common`, `error-handling`, and `kafka`; no threshold or exclusion was changed.

## Superseding current measured inventory — 2026-08-24 (catalog contract batch)

Current aggregate from `build/reports/jacoco/aggregate/jacoco.xml`: **4,023/4,922 lines (81.7351%)**, **5,968/8,644 branches (69.0421%)**. Remaining: **899 lines** and **2,676 branches**. Tests: **606** in **160** Kotlin test files. Phase 10A remains **NO-GO**.

| Priority | Service | Class/function | Missed lines | Missed branches | Missing path / next action |
| --- | --- | --- | ---: | ---: | --- |
| P1 | media | `ApplicationKt` | 10 | 23 | route/bootstrap alternatives |
| P1 | search | `ApplicationKt` | 10 | 22 | route/bootstrap alternatives |
| P0 | cart | `ApplicationKt` | 30 | 20 | cart route/application guards |
| P0 | checkout | `AddressWire` serializer | 0 | 19 | remaining contract-default paths; generated |
| P0 | payment | `ApplicationKt` | 26 | 18 | payment route/state guards |
| P0 | order | `ApplicationKt` | 25 | 18 | order route/state guards |
| P1 | wishlist | `ApplicationKt` | 27 | 17 | wishlist route/application guards |
| P1 | catalog | `ApplicationKt` | 14 | 17 | catalog route/application guards |
| P0 | pricing | `ApplicationKt` | 11 | 17 | pricing route/application guards |
| P0 | inventory | `ApplicationKt` | 65 | 16 | inventory lifecycle/error paths |
| P0 | promotion | `ApplicationKt` | 27 | 16 | promotion application/route paths |
| P1 | category | `ApplicationKt` | 21 | 16 | category lifecycle/route paths |
| P1 | notification | `ApplicationKt` | 20 | 16 | delivery application paths |
| P0 | seller | `ApplicationKt` | 17 | 16 | seller isolation/ledger paths |
| P0 | media | `MediaStorage` | 4 | 1 | provider operations need a safe injected boundary |

## Superseding latest measured inventory — 2026-08-24 (media/search/shared batch)

Current aggregate from `build/reports/jacoco/aggregate/jacoco.xml`: **4,023/4,922 lines (81.7351%)**, **5,966/8,644 branches (69.0190%)**. Remaining: **899 lines** and **2,678 branches**. Tests: **604** in **160** Kotlin test files. Phase 10A remains **NO-GO**.

| Priority | Service | Class/function | Missed lines | Missed branches | Missing path / next action |
| --- | --- | --- | ---: | ---: | --- |
| P0 | checkout | `AddressWire` serializer | 0 | 19 | contract-valid sparse/default combinations; generated |
| P1 | search | `SearchConsumer.apply` | 0 | 10 | remaining string-dispatch edges; all semantic event arms already exercised |
| P0 | promotion | `PromotionRepository` | 0 | 11 | inspect remaining eligibility/persistence branch inputs |
| P1 | review | `ReviewRepository` | 0 | 9 | inspect ingestion/moderation nullable/error outcomes |
| P0 | seller | `SellerRepository` | 1 | 7 | inspect seller event/transition outcomes |
| P1 | cms | `CmsRepository` | 0 | 7 | inspect persistence lifecycle outcomes |
| P1 | flags | `FlagRepository` | 0 | 5 | inspect validation/targeting outcomes |
| P1 | catalog | `CatalogRepository` | 0 | 4 | inspect persistence lifecycle outcomes |
| P0 | order | `OrderRepository` | 0 | 7 | inspect outbox/return mapping outcomes |
| P0 | media | `MediaStorage` | 4 | 1 | provider operations require an injected boundary fake |

Shared module exact status remains: `common` 37/37 lines and 43/46 branches; `error-handling` 32/32 lines and 18/20 branches; `kafka` 109/109 lines and 61/64 branches. Contract-valid serializer tests were added, but the generated/coroutine residuals remain.

## Superseding latest measured inventory — 2026-08-24 (post-Catalog/Seller batch)

Current aggregate from `build/reports/jacoco/aggregate/html/index.html` and `jacoco.xml`: **4,011/4,922 lines (81.4913%)**, **5,962/8,644 branches (68.9727%)**. Remaining: **911 lines** and **2,682 branches**. Tests: **598** in **159** Kotlin test files. Phase 10A remains **NO-GO**.

| Priority | Service | Class/function | Missed lines | Missed branches | Missing path / next action |
| --- | --- | --- | ---: | ---: | --- |
| P1 | media | `ApplicationKt` | 10 | 23 | reachable route/configuration alternatives |
| P1 | search | `ApplicationKt` | 10 | 22 | reachable route/configuration alternatives |
| P0 | cart | `ApplicationKt` | 30 | 20 | cart application/route guards |
| P0 | checkout | `AddressWire` | 0 | 19 | generated sparse-field serializer paths; no reflection-only tests |
| P0 | payment | `ApplicationKt` | 26 | 18 | payment route/state guards |
| P0 | order | `ApplicationKt` | 25 | 18 | order state/route guards |
| P1 | wishlist | `ApplicationKt` | 27 | 17 | wishlist route/application guards |
| P1 | catalog | `ApplicationKt` | 14 | 17 | catalog lifecycle/ownership guards |
| P0 | pricing | `ApplicationKt` | 11 | 17 | pricing route/application guards |
| P0 | inventory | `ApplicationKt` | 65 | 16 | inventory lifecycle/error paths |
| P0 | promotion | `ApplicationKt` | 27 | 16 | promotion application/route paths |
| P0 | seller | `ApplicationKt` | 17 | 16 | seller isolation/ledger paths |

PromotionRepository’s exact time-boundary batch reduced its `calculate` method from 6 to 4 missed branches; five persistence/serialization-related branch groups remain. No exclusions or threshold changes were made.

## Latest measured inventory — 2026-08-23

Current aggregate: **4,438/5,539 lines (80.1228%)** and **5,945/8,636 branches (68.8397%)**. Remaining: **1,101 lines** and **2,691 branches**. Tests: **597** in **159** Kotlin test files. Phase 10A remains **NO-GO**.

| Priority | Service | Class/function | Missed lines | Missed branches | Missing path / next action |
| --- | --- | --- | ---: | ---: | --- |
| P1 | media | `ApplicationKt` | 10 | 23 | reachable route/configuration alternatives |
| P1 | search | `ApplicationKt` | 10 | 22 | reachable route/configuration alternatives |
| P0 | cart | `ApplicationKt` | 30 | 20 | cart application/route guards |
| P0 | checkout | `AddressWire` | 0 | 19 | generated sparse-field serializer paths; do not use reflection-only tests |
| P0 | payment | `ApplicationKt` | 26 | 18 | payment route/state guards |
| P0 | order | `ApplicationKt` | 25 | 18 | order state/route guards |
| P1 | wishlist | `ApplicationKt` | 27 | 17 | wishlist route/application guards |
| P1 | catalog | `ApplicationKt` | 14 | 17 | catalog lifecycle/ownership guards |
| P0 | pricing | `ApplicationKt` | 11 | 17 | pricing route/application guards |
| P0 | inventory | `ApplicationKt` | 65 | 16 | inventory lifecycle/error paths |
| P0 | promotion | `ApplicationKt` | 27 | 16 | promotion application/route paths |
| P0 | seller | `ApplicationKt` | 17 | 16 | seller isolation/ledger paths |

Shared module exact status: `shared:common` 37/37 lines and 43/46 branches; `shared:error-handling` 32/32 lines and 18/20 branches; `shared:kafka` 109/109 lines and 61/64 branches. Their hard module gates remain FAIL because the remaining branches are generated serialization masks and one coroutine launch/resume path.

## Fresh clean inventory — 2026-08-23

Current aggregate: **4,410/5,512 lines (80.0073%)** and **5,909/8,638 branches (68.4070%)**. Remaining: **1,102 lines** and **2,729 branches**. Tests: **582** in **157** files. Phase 10A remains **NO-GO**.

| Priority | Service | Class/function | Missed lines | Missed branches | Next test focus |
| --- | --- | --- | ---: | ---: | --- |
| P0 | checkout | `ApplicationKt`, saga/application wiring | 8 | 15 | execute actual failure, retry, compensation, recovery and idempotency routes |
| P0 | identity | `ApplicationKt`, application/security wiring | 59 | 6 | route/application dependency and authorization failures |
| P0 | promotion | `ApplicationKt`, business/application wiring | 27 | 16 | eligibility, limits, restrictions and redemption outcomes |
| P1 | admin | `ApplicationKt.module` | 1 | 25 | proxy, permission and bulk-job outcomes |
| P0 | seller | `ApplicationKt` | 17 | 16 | seller isolation, lifecycle and ledger outcomes |
| P1 | inventory | `ApplicationKt` | 65 | 16 | full route/application lifecycle and health/error paths |
| P0 | payment | `ApplicationKt` | 26 | 18 | application state/reconciliation/idempotency paths |
| P1 | catalog | `ApplicationKt` | 14 | 17 | lifecycle, ownership and route errors |
| P1 | notification | `ApplicationKt` | 19 | 15 | preference, delivery lifecycle and DLQ paths |
| P1 | search | `ApplicationKt` | 10 | 22 | reachable route/configuration alternatives |

Shared verification status: `common` 3 generated branches missed; `error-handling` 2 generated branches missed; `kafka` 3 branches missed, including 2 generated `EventEnvelope` constructor branches and 1 coroutine launch/resume branch. These are not treated as covered without a reachable behavioral test.

## Superseding fresh inventory — 2026-08-23

Latest aggregate counters: **3,979/4,892 lines (81.3369%)** and **5,907/8,638 branches (68.3839%)**. Remaining: **913 lines** and **2,731 branches**. The hard 100% gate remains enabled and Phase 10A is **NO-GO**.

Top measured class-level branch concentrations:

| Priority | Service | Class | Missed branches | Missing path / next action |
| --- | --- | --- | ---: | --- |
| P1 | notification | `ApplicationKt.module.new Function2` | 25 | consumer callback outcomes; inspect reachable wiring paths |
| P1 | admin | `ApplicationKt.module.new Function2` | 25 | proxy/bulk orchestration outcomes |
| P1 | media | `ApplicationKt` | 23 | route/configuration guards |
| P1 | search | `ApplicationKt` | 22 | route/configuration alternatives |
| P0 | cart | `ApplicationKt` | 20 | route/configuration guards |
| P0 | checkout | `AddressWire` | 19 | sparse/complete serializer paths |
| P0 | payment | `ApplicationKt` | 18 | payment route/state guards |
| P0 | order | `ApplicationKt` | 18 | order route/state guards |
| P2 | wishlist | `ApplicationKt` | 17 | wishlist route/configuration guards |
| P1 | catalog | `ApplicationKt` | 17 | cache/permission/internal guards |
| P0 | pricing | `ApplicationKt` | 17 | pricing route/configuration guards |
| P0 | inventory | `ApplicationKt` | 16 | reservation/authorization routes |
| P0 | promotion | `ApplicationKt` | 16 | promotion route/configuration guards |
| P1 | category | `ApplicationKt` | 16 | category route/configuration guards |
| P0 | seller | `ApplicationKt` | 16 | ownership/downstream guards |

Reachable business targets to inspect next include PromotionRepository calculation/eligibility, SearchConsumer event handling, CmsRepository list/update/schedule branches, IdentityService registration/OTP/login branches, SellerRepository event/lifecycle branches, RefundRepository approval/outbox branches, and RecommendationRepository cold-start/event timestamp branches. Generated/private serializer and startup wiring markers remain explicitly open; none are excluded.

Fresh aggregate measurement from `build/reports/jacoco/aggregate/jacoco.xml` on 2026-08-23.

## Current counters

| Counter | Covered | Total | Uncovered | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,972 | 4,892 | 920 | 81.1938% |
| Branches | 5,872 | 8,638 | 2,766 | 67.9787% |

The 100% JaCoCo gate is unchanged. No exclusions, threshold changes, or test rewrites were used.

## Top 20 branch inventory

| Priority | Service/package | Class | Exact branch area | Missed branches | Required behavioral path | Status |
| --- | --- | --- | --- | ---: | --- | --- |
| P1 | notification | `ApplicationKt$module$1` | consumer callback coroutine | 25 | delivery success/failure/retry/DLQ outcomes | OPEN |
| P1 | admin | `ApplicationKt$module$1` | orchestration callback coroutine | 25 | downstream success/failure/timeout/job outcomes | OPEN |
| P1 | media | `ApplicationKt` | configuration and route guards | 23 | valid/invalid auth, ownership, request, storage paths | OPEN |
| P1 | search | `ApplicationKt` | configuration and route alternatives | 22 | route query, authorization, status boundaries | OPEN |
| P0 | cart | `ApplicationKt` | route/configuration guards | 20 | cache, auth, ownership, availability outcomes | OPEN |
| P0 | inventory | `ApplicationKt` | route/configuration guards | 18 | readiness, auth, reservation and persistence outcomes | OPEN |
| P0 | payment | `ApplicationKt` | route/configuration guards | 18 | provider, auth, lifecycle and failure outcomes | OPEN |
| P0 | order | `ApplicationKt` | route/configuration guards | 18 | state, authorization and dependency outcomes | OPEN |
| P0 | seller | `ApplicationKt` | ownership/downstream guards | 18 | same-owner, IDOR, role and downstream outcomes | OPEN |
| P2 | wishlist | `ApplicationKt` | route/configuration guards | 17 | auth, ownership, mutation and persistence outcomes | OPEN |
| P1 | catalog | `ApplicationKt` | cache/permission/internal guards | 17 | cache and authorization outcomes | OPEN |
| P0 | pricing | `ApplicationKt` | route/configuration guards | 17 | financial validation and provider outcomes | OPEN |
| P0 | promotion | `ApplicationKt` | route/configuration guards | 16 | auth, idempotency and internal-token outcomes | OPEN |
| P1 | category | `ApplicationKt` | route/configuration guards | 16 | auth, lifecycle and cache outcomes | OPEN |
| P0 | shipping | `ApplicationKt` | route/configuration/provider guards | 15 | provider, auth and webhook outcomes | OPEN |
| P0 | refund | `ApplicationKt` | route/configuration/provider guards | 15 | financial validation and provider outcomes | OPEN |
| P1 | notification | `ApplicationKt` | route/configuration guards | 15 | preference, provider and authorization outcomes | OPEN |
| P0 | checkout | `ApplicationKt` | route/configuration and saga wiring | 15 | idempotency, auth and dependency outcomes | OPEN |

## Reachable business branch targets

| Class | Method | Missed branches | Next test state |
| --- | --- | ---: | --- |
| `PromotionRepository` | `calculate` | 6 | remaining eligibility/short-circuit/discount boundary outcomes |
| `IdentityService` | `register` | 4 | contact/name/password validation combinations |
| `IdentityRepository` | `rotateRefreshToken` | 1 | valid rotation versus missing/expired/reused token result |
| `InventoryRepository` | `maybeLowStock` | 3 | threshold, no-threshold, and nullable stock outcomes |
| `InventoryRepository` | `transition$lambda$0` | 5 | valid/invalid reservation state transitions |
| `SellerRepository` | `apply$lambda$0` | 5 | event deduplication and malformed payload outcomes |
| `SellerRepository` | `transition$lambda$0` | 2 | persistence transition and stale-version outcomes |
| `HttpShippingProvider` | response mapping | 6+ | missing identifiers, optional values, status and transport outcomes |

The latest batches added Search status/route boundary tests, Inventory privileged-owner coverage, Review malformed/DLQ payload cases, and a behavior-preserving Promotion redemption helper cleanup. The aggregate now has 2,766 missed branches; the remaining inventory is still open.

## Fresh XML inventory (2026-08-25)

| Service/package | Class/method | Missed branches | Required scenario / finding | Status |
| --- | --- | ---: | --- | --- |
| shared:common | `Money` generated constructor | 1 | serializer mask/default constructor path | open |
| shared:common | `CursorPage` generated constructor | 1 | serializer mask path | open |
| shared:common | `CursorPageRequest` generated constructor | 1 | serializer mask/default/validation path | open |
| shared:error-handling | `ApiError` generated constructor | 1 | serializer required/optional mask path | open |
| shared:error-handling | `FieldViolation` generated constructor | 1 | serializer required-field mask path | open |
| shared:kafka | `KafkaConsumerWorker` coroutine entry | 1 | compiler-generated launch entry branch | open |
| order | `OrderRepository.outbox` | 6 | actual response payload variants; `else` is not reached by production call sites | open |
| promotion | `PromotionRepository.transition$lambda$0` | 3 | redemption transition/persistence outcomes | open |
| seller | `SellerRepository.apply$lambda$0` | 3 | event payload/deduplication outcomes | open |
| checkout | `runCheckoutSaga` | 1 | remaining persisted recovery combination | open |

The current aggregate has **2,319 missed branches**. This inventory is regenerated from the current XML and is not a manual completion claim.

## Current status

Uncovered lines: **889**. Uncovered branches: **2,319**. Phase 10A remains **NO-GO** until both are zero and `./gradlew jacocoTestCoverageVerification` succeeds.

## Final verification snapshot (2026-08-25)

The post-build verification command returned non-zero. The remaining module-level failures are:

| Module | Branches covered/total | Missed branches | Exact remaining inventory |
| --- | ---: | ---: | --- |
| `shared:common` | 43/46 | 3 | generated constructor masks for `Money`, `CursorPage`, and `CursorPageRequest` |
| `shared:error-handling` | 18/20 | 2 | generated constructor masks for `FieldViolation` and `ApiError` |
| `shared:kafka` | 62/64 | 2 | generated `EventEnvelope` constructor mask and `KafkaConsumerWorker` coroutine entry state |

These remain open; no exclusion or threshold change was applied.
