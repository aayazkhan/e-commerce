# Phase 10A coverage progress

## Latest clean checkpoint — 2026-08-24

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,059 | 4,954 | 895 | 81.9338% |
| Branches | 6,289 | 8,644 | 2,355 | 72.7557% |
| Instructions | 110,110 | 148,219 | 38,109 | 74.2887% |
| Methods | 3,795 | 5,142 | 1,347 | 73.8040% |
| Classes | 881 | 1,126 | 245 | 78.2416% |

Tests: **631** across **164** Kotlin test files. Compared with the fresh start of this continuation (`6,260/8,644` branches), targeted envelope/response serialization contracts and promotion rejection behavior eliminated **29 branches**; the subsequent shared model-copy assertions added four behavioral tests but did not change JaCoCo counters. Unit/report: **PASS**. JaCoCo verification: **FAIL**. Phase 10A: **NO-GO**.

## Superseding current checkpoint — 2026-08-24 (shared-boundary and identity-route batch)

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,055 | 4,954 | 899 | 81.8530% |
| Branches | 5,973 | 8,644 | 2,671 | 69.1000% |
| Instructions | 108,307 | 148,219 | 39,912 | 73.0723% |
| Methods | 3,723 | 5,142 | 1,419 | 72.4037% |
| Classes | 880 | 1,126 | 246 | 78.1528% |

Compared with the immediately preceding forced aggregate (4,023/4,922 lines and 5,968/8,644 branches), this batch covered **5 additional branches**. The line denominator grew because the safe `ServiceDatabase` production seam is now measured; its boundary behavior is tested, while the real Hikari/Flyway constructor path remains integration-boundary code. Tests: **613** across **162** Kotlin test files. Unit/report: **PASS**; hard gate: **FAIL**; Phase 10A: **NO-GO**.

## Superseding current checkpoint — 2026-08-24 (catalog contract batch)

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,968 | 8,644 | 2,676 | 69.0421% |
| Methods | 3,706 | 5,127 | 1,421 | 72.2840% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **606** across **160** Kotlin test files. Compared with the preceding aggregate, this batch eliminated **2 branches** (catalog `Product` serializer) with no line-count change. Unit/report: **PASS**; hard gate: **FAIL**; Phase 10A: **NO-GO**.

## Superseding current checkpoint — 2026-08-24 (media/search/shared batch)

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,966 | 8,644 | 2,678 | 69.0190% |
| Instructions | 108,055 | 148,120 | 40,065 | 72.9510% |
| Methods | 3,705 | 5,127 | 1,422 | 72.2645% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **604** across **160** Kotlin test files. Compared with the previous authoritative aggregate, this batch covered **1 additional line** and **2 additional branches** in the aggregate report. The successful SearchConsumer worker test closed its `runLoop` branch pair; ImageProcessor is now behaviorally covered for derivative success, invalid image, and storage failure. Shared contract tests pass but the three shared module branch residuals are unchanged. Unit/report: **PASS**; hard gate: **FAIL**; Phase 10A: **NO-GO**.

## Post-Catalog/Seller authoritative checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,011 | 4,922 | 911 | 81.4913% |
| Branches | 5,962 | 8,644 | 2,682 | 68.9727% |
| Instructions | 107,827 | 148,118 | 40,291 | 72.7980% |
| Methods | 3,701 | 5,127 | 1,426 | 72.1865% |
| Classes | 875 | 1,123 | 248 | 77.9163% |

Tests: **598** across **159** Kotlin test files. Compared with the prior authoritative checkpoint, missed branches fell from **2,689 to 2,682**. Catalog persistence mapping, Seller malformed-event handling, Review malformed order items, and Audit DLQ/default actor paths were validated. JaCoCo report generation and unit tests pass; the unchanged 100% gate remains **FAIL**.

## Superseding latest measured checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,011 | 4,922 | 911 | 81.4913% |
| Branches | 5,947 | 8,636 | 2,689 | 68.8629% |
| Instructions | 107,754 | 148,100 | 40,346 | 72.7576% |
| Methods | 3,700 | 5,127 | 1,427 | 72.1670% |
| Classes | 874 | 1,123 | 249 | 77.8272% |

Tests: **598** across **159** Kotlin test files. Compared with the prior forced-rerun aggregate of 80.1228% / 68.8397%, this report is the authoritative regenerated HTML/XML aggregate and records **2,689** missed branches. The PromotionRepository time-window batch passed and reduced its calculation branch misses by two from the pre-batch module report. The hard 100% gate remains FAIL.

## Latest measured checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,438 | 5,539 | 1,101 | 80.1228% |
| Branches | 5,945 | 8,636 | 2,691 | 68.8397% |
| Instructions | 107,729 | 148,077 | 40,348 | 72.7520% |
| Methods | 3,699 | 5,126 | 1,427 | 72.1615% |
| Classes | 874 | 1,123 | 249 | 77.8272% |

Tests: **597** across **159** Kotlin test files. Compared with the prior measured 80.0073% / 68.4070% checkpoint, this run improved to 80.1228% / 68.8397%. The latest batch added meaningful Checkout recovery tests, completed the CMS `APPROVED` transition arm, and covered two feature-flag and two promotion decision paths. The hard 100% gate remains FAIL.

## Fresh clean measurement — 2026-08-23

The clean rerun and aggregate JaCoCo report are the current source of truth. The previous 4,892-line denominator was stale; the regenerated report contains 5,512 lines.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,410 | 5,512 | 1,102 | 80.0073% |
| Branches | 5,909 | 8,638 | 2,729 | 68.4070% |
| Instructions | 107,405 | 148,033 | 40,628 | 72.5548% |
| Methods | 3,692 | 5,119 | 1,427 | 72.1235% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

Current tests: **582** across **157** Kotlin test files. The hard gate remains enabled and Phase 10A remains **NO-GO**.

The shared-module verification blockers are now: `common` 3 missed generated serialization branches, `error-handling` 2 missed generated serialization branches, and `kafka` 3 missed branches (2 generated `EventEnvelope` constructor branches and 1 coroutine launch/resume branch). Kafka close-failure behavior is covered by `KafkaConsumerWorkerTest`.

## Superseding fresh checkpoint — 2026-08-23

Measured after the Cart, FlagRepository, ReviewRepository, RefundRepository, CMS, and Recommendation batches from the aggregate JaCoCo report:

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,979 | 4,892 | 913 | 81.3369% |
| Branches | 5,907 | 8,638 | 2,731 | 68.3839% |
| Instructions | 107,360 | 148,033 | 40,673 | 72.5244% |
| Methods | 3,691 | 5,119 | 1,428 | 72.1039% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

Declared tests: 575 across 157 Kotlin test files. The clean unit/report pipeline passed and the clean build passed. The hard 100% verification remains FAIL; Phase 10A remains NO-GO.

This checkpoint added behavioral coverage for Cart merge quantity capping, feature-flag missing-target dimensions and outbox publication, refund outbox publication, CMS missing-page/client-version/schedule branches, and recommendation cold-start/invalid-timestamp fallbacks. The Cart and CMS fakes were corrected to honor the SQL update and ID predicates; no production behavior was weakened.

## Current fresh checkpoint — 2026-08-23

Measured from `build/reports/jacoco/aggregate/jacoco.xml` after the latest Search, Inventory, Review, and Promotion work:

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,972 | 4,892 | 920 | 81.1938% |
| Branches | 5,872 | 8,638 | 2,766 | 67.9787% |
| Methods | 3,687 | 5,119 | 1,432 | 72.0258% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

Declared tests: 572 across 157 Kotlin test files. Compared with the immediately preceding aggregate checkpoint (3,968/4,888 lines and 5,854/8,636 branches), this batch added 4 covered lines and 18 covered branches. The aggregate report and unit tests pass; the hard 100% gate remains FAIL.

Latest meaningful changes:

- Search client transport/status-boundary and search-route query/reindex tests.
- Inventory privileged-owner route coverage.
- Review malformed order payload and DLQ truncation/default cases.
- Promotion redemption helper simplification preserving `FOR UPDATE` behavior.

No JaCoCo threshold, exclusion, or existing meaningful test was changed.

## Historical branch-elimination checkpoint — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,927 | 4,880 | 953 | 80.4713% |
| Branches | 5,791 | 8,644 | 2,853 | 66.9944% |
| Methods | 3,666 | 5,115 | 1,449 | 71.6716% |
| Classes | 867 | 1,122 | 255 | 77.2727% |

Compared with the preceding measured checkpoint (3,927/4,880 lines and 5,790/8,644 branches), this batch eliminated 1 missed branch and 0 missed lines. It added a deterministic notification quiet-hours expiry case. The aggregate `./gradlew test jacocoTestReport` passed; the hard 100% verification gate remains FAIL because shared bundle rules and aggregate missed production branches remain.

This historical checkpoint recorded 552 declared tests across 156 Kotlin test files. No JaCoCo configuration, threshold, exclusion, or existing meaningful test was weakened.

## Fresh current checkpoint — 2026-08-23

The latest aggregate supersedes the older historical rows below:

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,895 | 4,877 | 982 | 79.8647% |
| Branches | 5,640 | 8,644 | 3,004 | 65.2476% |
| Methods | 3,604 | 5,114 | 1,510 | 70.4732% |
| Classes | 864 | 1,121 | 257 | 77.0740% |

Latest targeted additions: RedisCache command success/failure/TTL behavior, privileged-role notification authorization, and PricingRepository sale/end-date persistence, update/delete, and outbox paths. Test count is 531 declared tests in 154 Kotlin test files. The 100% gate remains enabled and the current status is **NO-GO**.

## Current branch-focused checkpoint

| Checkpoint | Line | Branch | Methods | Classes | Declared tests | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Cart/inventory/promotion/flags/pricing batch | 3,852/4,862 (79.2267%) | 5,457/8,646 (63.1191%) | 3,564/5,101 | 861/1,120 | 506 | Gate failed as expected |
| Media/OAuth/analytics/notification/refund/catalog/admin batch | 3,860/4,862 (79.3912%) | 5,552/8,646 (64.2147%) | 3,575/5,101 | 862/1,120 | 512 | Gate failed as expected |
| Current targeted business-flow batch | 3,869/4,862 (79.5763%) | 5,609/8,646 (64.8739%) | 3,584/5,101 | 862/1,120 | 524 | Gate failed as expected |
| Redis/notification/pricing branch batch | 3,895/4,877 (79.8647%) | 5,640/8,644 (65.2476%) | 3,604/5,114 | 864/1,121 | 531 | Gate failed as expected |

Measured on 2026-08-23 from `build/reports/jacoco/aggregate/jacoco.xml`. The Phase 10A gate remains enabled at 100% line and branch coverage; no production code is excluded.

## Aggregate checkpoints

| Checkpoint | Line | Branch | Instruction | Fast declared tests | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Baseline | 421/4,346 (9.69%) | 150/8,604 (1.74%) | 4,175/145,353 (2.87%) | 42 executed | Gate failed as expected |
| Domain/model batch | 681/4,346 (15.67%) | 604/8,604 (7.02%) | 18,124/145,353 (12.46%) | 99 declared | Unit suites passed |
| Previous continuation checkpoint | 1,052/4,346 (24.22%) | 1,121/8,624 (13.00%) | 25,482/145,618 (17.50%) | 152 fast declared | Gate failed as expected |
| Current checkpoint | 3,166/4,428 (71.50%) | 3,322/8,634 (38.48%) | 77,358/146,879 (52.67%) | 283 fast declared | Gate still fails |
| Latest measured checkpoint | 3,339/4,538 (73.58%) | 3,645/8,652 (42.13%) | 83,725/147,108 (56.91%) | 308 fast declared | Gate still fails |
| Recommendation checkpoint | 3,358/4,584 (73.25%) | 3,670/8,652 (42.42%) | 84,067/147,143 (57.13%) | 310 fast declared | Gate still fails |
| Current continuation checkpoint | 3,616/4,759 (75.98%) | 3,921/8,652 (45.32%) | 87,899/147,546 (59.57%) | 339 fast declared | Gate still fails |
| Branch-focused continuation checkpoint | 3,717/4,843 (76.75%) | 4,076/8,654 (47.10%) | 91,681/147,755 (62.05%) | 358 fast declared | Gate still fails |
| Media/search/wishlist checkpoint | 3,768/4,862 (77.50%) | 4,275/8,646 (49.44%) | 94,742/147,805 (64.10%) | 368 fast declared | Gate still fails |
| Recommendation repository checkpoint | 3,781/4,862 (77.77%) | 4,760/8,646 (55.05%) | 96,827/147,805 (65.51%) | 395 fast declared | Gate still fails |
| Pricing/refund persistence checkpoint | 3,804/4,862 (78.24%) | 4,834/8,646 (55.91%) | 98,443/147,805 (66.60%) | 407 fast declared | Gate still fails |
| Branch exhaustion checkpoint | 3,829/4,862 (78.76%) | 5,323/8,646 (61.57%) | 101,641/147,805 (68.76%) | 470 declared | Gate still fails |
| Shared contract serialization checkpoint | 3,836/4,862 (78.90%) | 5,339/8,646 (61.75%) | 101,862/147,805 (68.91%) | 473 declared | Gate still fails |
| Checkout saga failure/compensation checkpoint | 3,836/4,862 (78.90%) | 5,346/8,646 (61.83%) | 101,892/147,805 (68.93%) | 481 declared | Gate still fails |

The denominator increased because the new tests execute additional generated serialization and provider paths, which are included in the unchanged JaCoCo production class set. Current totals are taken directly from the aggregate XML, not rounded service summaries.

## Completed test batches

- Added meaningful serialization/default/optional-field tests for cart, checkout, notification, recommendation, feature flags, shipping, order, payment, media, inventory, pricing, promotion, catalog, category, admin, analytics, audit, refund, review, and wishlist models.
- Added boundary tests for category/catalog validation, pricing tax and unit-price selection, promotion discount arithmetic, media signatures, and inventory quantities.
- Added exhaustive documented transition matrices for order, payment, and shipping state machines, including same-state idempotency and invalid transitions.
- Added HTTP fixture tests for payment and shipping provider status mapping, HMAC webhook verification, dependency failures, OAuth and challenge delivery, OpenSearch search/index/alias flows, and wishlist price/inventory enrichment.
- Added shared JWT verification, Redis-key, and internal/downstream HTTP boundary tests.
- Added repository validation tests for refund, review, order, payment, inventory, notification, CMS, and admin services, including malformed financial inputs, lifecycle payloads, retry policy boundaries, and invalid JSON.
- Added cart ownership/idempotency validation and promotion financial, coupon, timestamp, cart-input, and idempotency validation tests.
- Added identity repository boundary tests and identity application tests for persistence errors, registration, login, refresh, OAuth, OTP, verification, sessions, profiles, addresses, rate limits, and admin operations.
- Added CMS lifecycle tests for optimistic locking, transitions, scheduled publish/unpublish, rollback, due jobs, outbox persistence, and transaction rollback.
- Added stateful cart repository tests for mutation/idempotency/merge/expiry/outbox behavior and promotion calculation tests for all promotion types and exact financial/eligibility outcomes.
- Added stateful inventory repository tests for reservation normalization/idempotency, stock invariants, ownership/state transitions, expiry, adjustments, movements, and outbox behavior.
- Added analytics repository tests for exact revenue/order/checkout metrics, inbox/applied-event deduplication, replay, malformed events, PII filtering, and DLQ fallback.
- Added seller repository tests for onboarding/profile versioning, lifecycle authorization boundaries, seller-scoped orders and ledger entries, event deduplication, malformed events, DLQ writes, and outbox handling.
- Added notification repository behavior tests for inbox deduplication, localization/template fallback, preference suppression, delivery claiming, provider/webhook status handling, retry backoff/DLQ, device and in-app persistence, and consumer DLQ writes.
- Extended identity persistence tests for successful login state reset, profile updates, deactivation, and optional OTP record values.
- Added payment repository behavior tests for provider creation/idempotency, ownership reads, webhook amount/state validation, refunds and refund idempotency, provider/reconciliation failure paths, and outbox boundaries.
- Added audit repository behavior tests for event deduplication, PII/sensitive-field sanitization, append-only record mapping, filtered pagination, nullable DLQ events, IP hashing, and transaction rollback.
- Added injectable Kafka consumer/producer boundaries and tests for event decoding, manual offset commits, lag observation, malformed events, handler/DLQ failures, outer broker failures, shutdown, and DLQ publishing. Production behavior remains Kafka-backed by default; fakes are used only by unit tests.
- Added wishlist repository behavior tests for add/remove/clear idempotency, cursor pagination and malformed cursors, outbox publication, and empty/non-empty mutation side effects.
- Added catalog repository behavior tests for complete product/variant/media graph persistence, public/private reads, seller isolation, lifecycle transitions, pagination cursors, outbox publication, and duplicate SKU/slug conflict mapping.
- Added admin repository behavior tests for bulk-job creation/deduplication, job lookup and claiming, retryable and terminal completion, outbox listing/publication, and transaction rollback.
- Added identity DTO mapping/serialization tests and identity route tests for authentication, authorization, profile/address responses, admin status validation, login, OTP, and password-reset HTTP behavior.
- Added an injectable Redis rate-limit command boundary and tests for first-hit expiry, under-limit behavior, retryable limit rejection, health status, and cleanup; the production Redis constructor remains unchanged for application wiring.
- Added category route seams (`CategoryStore`/`CategoryCache`) and HTTP tests for public cache hit/miss, children/tree/not-found reads, authentication/permission errors, admin lifecycle operations, reorder, and request conversion. The production module still uses the concrete PostgreSQL repository and Redis adapter by default.
- Added checkout repository behavior tests for idempotent saga start/replay/conflict, ownership reads, checkpoint field preservation, recoverable failure truncation, and outbox listing/acknowledgement.
- Added catalog route seams (`CatalogStore`/`CatalogCache`) and HTTP tests for paginated/cache-backed public reads, slug/product not-found behavior, authenticated seller/admin writes, ownership mapping, lifecycle status changes, cache invalidation, internal publish authentication, and nested product request conversion.
- Added checkout route seams (`CheckoutStore`/`CheckoutWorkflow`) and HTTP tests for authenticated validation, required idempotency keys, owned/not-found reads, retry handling, and workflow execution while retaining the concrete saga implementation for production wiring.
- Added admin route seams (`AdminStore`/`AdminProxy`) and HTTP tests for permission boundaries, proxy path/method/body forwarding, downstream status mapping, bulk publish job creation, job lookup, and not-found behavior while retaining concrete database/HTTP wiring in the module.
- Added complete identity HTTP DTO serialization coverage for all request/response types, nested authentication/OTP responses, nullable fields, and default decoding forms.
- Added promotion domain completeness tests for every enum state, full promotion/coupon/redemption wire models, nullable/default fields, and empty-collection behavior.
- Added inventory route seams (`InventoryStore`) and HTTP tests for public reads, internal actor authentication, reservation ownership/lifecycle, admin creation/adjustment/movement routes, permissions, and request mapping while retaining concrete repository wiring through an adapter.
- Added cart route seams (`CartStore`, `CartPricing`, `CartInventory`, and `CartCache`) and HTTP tests for guest-token creation/cache hits, authenticated ownership, idempotent mutations, stock conflicts, price/stock validation warnings, merge, clear/remove/update, and dependency/authentication errors.
- Added seller route seams (`SellerStore`/`SellerProxy`) and HTTP tests for seller authentication and ownership, product IDOR prevention, downstream forwarding/error mapping, onboarding/profile, seller orders/inventory/promotions/analytics/ledger, and admin status/ledger operations while retaining Kafka/database/HTTP production wiring.
- Added checkout saga dependency seams and deterministic tests for completed-idempotency, promotion commit, payment action-required, invalid quote, retryable/generic failure compensation, reservation release, order transitions, shipment failure, and refund compensation.
- Added CMS store/cache route seams and HTTP tests for public cache hit/miss, lifecycle and version operations, authorization, not-found/conflict mapping, and cache invalidation while preserving concrete database/Redis production wiring.
- Added analytics store route seams and HTTP tests for exact summary windows, replay input forwarding, permission boundaries, and typed downstream failures.
- Added notification, order, and payment route seams and HTTP tests for user/internal/admin authorization, ownership, idempotency headers, webhook signature/provider validation, refunds, lifecycle requests, and dependency error mapping.
- Added promotion store/cache route seams and HTTP tests for quote caching, seller/internal access, redemption transitions, admin/coupon permissions, idempotency, and dependency failures; added exact percentage discount boundary tests.
- Added refund, shipping, and review route seams and HTTP tests for authentication, ownership/not-found behavior, privileged/internal authorization, webhooks, moderation, idempotency, and typed dependency failures.
- Added identity configuration and outbox publisher boundary tests, feature-flag route/cache tests, inventory permission-fallback tests, checkout payment/quote branches, shared database validation tests, and complete media signature/size validation cases.
- Fixed a real inventory serialization defect found by reservation hashing: reservation/input payload types and enums now have generated Kotlin serializers.
- Fixed a real shared-security defect found by the new valid-token test: `shared/security` was missing the Kotlin serialization plugin, and the verifier now passes explicit serializers for its private JWT types. Valid HS256 tokens are now accepted and malformed/tampered tokens remain rejected.
- Added branch-focused nullable/default serializer cases for audit, notification, feature flags, CMS, and promotion models, including sparse JSON decoding rather than only round trips with defaults emitted.
- Added checkout downstream-client boundary tests for invalid/empty carts, quote totals, address/billing fallback, missing product variants/addresses, request headers, non-2xx dependencies, and all reservation/order/payment/promotion/shipping/refund operations.
- Added catalog/category/pricing cache corruption fallbacks, role/permission alternatives, query-default boundaries, and inventory blank-token/invalid-limit routes.
- Added a deterministic search consumer boundary for Kafka poll/commit/close, inbox deduplication, product lifecycle events, price updates, optional variants, malformed numeric payloads, and no-op event types.
- Added injectable media route boundaries and HTTP tests for presigning, upload validation, object upload, public lookup and URL mapping, completion ownership/checksum paths, delete cleanup, storage cleanup failure tolerance, and upload/delete authorization.
- Added injectable search route boundaries and HTTP tests for query normalization, suggestions, filters, reindex pagination, admin authorization, catalog failures, missing products, and malformed catalog pages.
- Added injectable wishlist route boundaries and HTTP tests for authenticated pagination, currency normalization, add aliases, delete aliases, clear behavior, invalid tokens, and path handling.
- Added explicit-null and omitted-default promotion payload tests for calculate requests, lines, quotes, responses, redemptions, and coupon-related optional fields.
- Added deterministic `RedisCacheTest` coverage for command success/failure, empty deletion, counter TTL behavior, SET-NX outcomes, and safe fallbacks through an internal command boundary.
- Added notification admin authorization coverage for privileged roles bypassing the explicit template permission list.
- Added `PricingRepositoryQuoteTest` coverage for sale/end-date persistence, successful and missing update/delete operations, mapped pending/empty outbox reads, and publication batches.

## Current service snapshots

These are package-level line/branch counters after the current checkpoint; they are progress measurements, not completion claims.

| Service package | Line | Branch |
| --- | ---: | ---: |
| cart | 195/244 | 232/349 |
| checkout | 126/163 | 286/522 |
| notification | 89/114 | 190/312 |
| recommendation | 35/64 | 118/188 |
| feature flags | 44/82 | 202/285 |
| shipping | 51/87 | 142/225 |
| order | 167/193 | 269/387 |
| payment | 129/157 | 201/309 |
| media | 38/85 | 99/211 |
| inventory | 129/213 | 226/343 |
| pricing | 49/73 | 190/298 |
| promotion | 153/184 | 631/802 |
| catalog | 124/143 | 380/507 |
| category | 146/173 | 193/295 |
| cms | 238/274 | 226/345 |
| audit | 60/65 | 118/218 |
| search | 109/139 | 265/376 |
| analytics | 56/75 | 149/231 |
| wishlist | 81/109 | 85/184 |
| refund | 46/82 | 101/178 |

The latest checkpoint also includes SearchIndexClient parser/status paths, Checkout sparse wire/client behavior, Audit filtering/malformed payload paths, Category lifecycle conflicts, SearchConsumer recovery, Promotion route/default serialization, Catalog cache/permission behavior, and Payment refund/webhook state paths. The current aggregate JaCoCo method counter is 3,584/5,101 and the class counter is 862/1,120.

## Fresh top-gap snapshot

The prior aggregate XML reported 531 declared tests across 154 Kotlin test files. The current fresh top classes are recorded in `coverage-heatmap.md` and `top-coverage-gaps.md`; the current aggregate is 3,972/4,892 lines and 5,872/8,638 branches.

The latest targeted batches added meaningful coverage for review event fallbacks and pagination, search HTTP status boundaries and consumer event variants, cart authenticated/guest/cache/mutation paths, promotion coupon-per-user and no-coupon release behavior, media permission/metadata/completion paths, and shipping provider invalid-response/status/signature paths.

## Remaining work

The largest remaining uncovered areas are repository transactions and application route wiring, especially inventory and checkout application setup, identity application setup, category/catalog/pricing, CMS, promotion, seller, notification, payment, media/search, and shared Kafka/database error branches. The full inventory is in [`uncovered-code-inventory.md`](uncovered-code-inventory.md). Integration, E2E, provider, load, soak, and chaos suites remain separate from the fast unit checkpoint. Mutation testing has not been run because no mutation-testing plugin is configured.

The current checkpoint is not 100%; Phase 10A therefore remains NO-GO until the exact aggregate line and branch counters both reach their configured 100% thresholds. The latest measured missed counters are 982 lines and 3,004 branches.

## Latest authoritative checkpoint (2026-08-25)

| Metric | Previous recorded baseline | Fresh result | Delta |
| --- | ---: | ---: | ---: |
| Lines covered | 4,059/4,954 | 4,064/4,953 | +5 covered; denominator changed after dead-code removal |
| Missed lines | 895 | 889 | -6 |
| Branches covered | 6,289/8,644 | 6,317/8,636 | +28 covered; denominator changed after dead-code removal and serialization coverage |
| Missed branches | 2,355 | 2,319 | -36 |
| JUnit XML test cases | 631 | 637 | +6 measured XML test cases |
| Kotlin/Java test source files | 164 | 167 | +3 source files |

The Kafka worker cancellation test closed the reported coroutine-loop branch gap; the explicit causation-id serialization test removed one generated serialization branch. The Review ingestion cleanup removed confirmed no-op JSON parsing branches without changing behavior. Recommendation malformed-array handling remains covered by a regression test. The three shared generated-constructor branches and the remaining startup/coroutine wrappers are still open. `jacocoTestCoverageVerification` remains FAIL.
