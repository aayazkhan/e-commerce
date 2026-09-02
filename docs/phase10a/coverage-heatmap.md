# Phase 10A coverage heatmap

## Latest authoritative heatmap — 2026-08-24

Source: `build/reports/jacoco/aggregate/jacoco.xml` after the clean test/report run.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,059 | 4,954 | 895 | 81.9338% |
| Branches | 6,289 | 8,644 | 2,355 | 72.7557% |
| Instructions | 110,110 | 148,219 | 38,109 | 74.2887% |
| Methods | 3,795 | 5,142 | 1,347 | 73.8040% |
| Classes | 881 | 1,126 | 245 | 78.2416% |

Tests: **631** declarations in **164** Kotlin test files. Unit/report: **PASS**. JaCoCo verification: **FAIL**. Phase 10A: **NO-GO**.

### Ranked package heatmap

| Package | Missed lines | Missed branches | Priority |
| --- | ---: | ---: | --- |
| promotion | 31 | 133 | P0 financial |
| admin | 12 | 128 | P1 authorization/orchestration |
| seller | 30 | 126 | P0 isolation/ledger |
| checkout | 37 | 115 | P0 Saga |
| identity.http | 0 | 114 | P0 security/routes |
| cart | 46 | 108 | P0 cart flow |
| order | 26 | 100 | P0 state machine |
| cms | 34 | 99 | P1 lifecycle |
| payment | 28 | 98 | P0 financial |
| inventory | 84 | 95 | P0 stock invariants |
| search | 29 | 95 | P1 indexing/events |
| notification | 25 | 92 | P1 delivery |
| catalog | 19 | 92 | P1 ownership/lifecycle |
| category | 27 | 91 | P1 lifecycle |
| media | 11 | 87 | P1 storage |

The largest measured concentration is application bootstrap code; the largest reachable non-bootstrap classes are tracked in `top-coverage-gaps.md` and `remaining-branch-inventory.md`.

## Superseding current measured heatmap — 2026-08-24 (shared-boundary and identity-route batch)

Source: `build/reports/jacoco/aggregate/jacoco.xml` after the forced aggregate rerun. These XML totals are authoritative.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,055 | 4,954 | 899 | 81.8530% |
| Branches | 5,973 | 8,644 | 2,671 | 69.1000% |
| Instructions | 108,307 | 148,219 | 39,912 | 73.0723% |
| Methods | 3,723 | 5,142 | 1,419 | 72.4037% |
| Classes | 880 | 1,126 | 246 | 78.1528% |

Tests: **613** declarations in **162** Kotlin test files. The batch added boundary tests for `ServiceDatabase`, `DatabaseFactory`, and `MediaStorage`, plus identity forwarded-IP/request metadata and cancelled-consumer lifecycle coverage. Unit/report: **PASS**; hard gate: **FAIL**; Phase 10A: **NO-GO**.

| Package | Missed lines | Missed branches | Priority |
| --- | ---: | ---: | --- |
| checkout | 37 | 209 | P0 |
| promotion | 31 | 168 | P0 |
| seller | 30 | 141 | P0 |
| admin | 12 | 133 | P0 |
| identity.http | 0 | 125 | P0/security |
| catalog | 19 | 116 | P1 |
| order | 26 | 114 | P0 |
| cart | 48 | 113 | P0 |
| cms | 34 | 112 | P1 |
| inventory | 84 | 102 | P0 |

The largest raw classes remain application bootstrap/route classes and generated serializers; the next reachable business targets are tracked in `top-coverage-gaps.md`.

## Superseding current measured heatmap — 2026-08-24 (catalog contract batch)

Source: `build/reports/jacoco/aggregate/jacoco.xml` after the forced aggregate rerun. These XML totals are authoritative.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,968 | 8,644 | 2,676 | 69.0421% |
| Instructions | 108,055 | 148,120 | 40,065 | 72.9510% |
| Methods | 3,706 | 5,127 | 1,421 | 72.2840% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **606** declarations in **160** Kotlin test files. The catalog contract batch added a sparse `Product` payload round-trip assertion and covered two generated serializer branches without reflection or exclusions. Unit/report: **PASS**; hard gate: **FAIL**; Phase 10A: **NO-GO**.

The package heatmap is unchanged except for catalog, which now has **116** missed branches; the largest measured packages remain checkout (209), promotion (168), seller (141), admin (133), identity.http (128), catalog (116), order (114), cart (113), and CMS (112).

## Superseding latest measured heatmap — 2026-08-24 (media/search/shared batch)

Source: `build/reports/jacoco/aggregate/jacoco.xml` and the generated HTML report after `./gradlew test jacocoTestReport --rerun-tasks`. These XML/HTML totals are authoritative.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,023 | 4,922 | 899 | 81.7351% |
| Branches | 5,966 | 8,644 | 2,678 | 69.0190% |
| Instructions | 108,055 | 148,120 | 40,065 | 72.9510% |
| Methods | 3,705 | 5,127 | 1,422 | 72.2645% |
| Classes | 877 | 1,123 | 246 | 78.0944% |

Tests: **604** declarations in **160** Kotlin test files. The ImageProcessor success/failure seam and SearchConsumer successful-worker path pass. Shared serialization contract tests pass, but their three module gates still report the same generated/coroutine branch edges. The hard 100% gate remains **FAIL**.

### Top measured packages by missed branches

| Package/service | Lines missed | Branches missed | Priority |
| --- | ---: | ---: | --- |
| checkout | 37 | 209 | P0 saga/business flow |
| promotion | 31 | 168 | P0 financial |
| seller | 30 | 141 | P0 isolation/ledger |
| admin | 12 | 133 | P1 orchestration |
| identity.http | 0 | 128 | P0 security/routes |
| catalog | 19 | 118 | P1 ownership/lifecycle |
| order | 26 | 114 | P0 state machine |
| cart | 48 | 113 | P0 cart flow |
| cms | 34 | 112 | P1 lifecycle |
| inventory | 84 | 102 | P0 stock invariants |
| notification | 25 | 100 | P1 delivery lifecycle |
| wishlist | 28 | 99 | P1 application flow |
| payment | 28 | 98 | P0 financial |
| category | 27 | 97 | P1 lifecycle |
| search | 29 | 95 | P1 indexing/event flow |

The largest reachable business methods remain concentrated in SearchConsumer (10 missed branches in event dispatch), PromotionRepository (11 persistence/calculation branches), ReviewRepository (9 ingestion/moderation branches), SellerRepository (7), CmsRepository (7), FlagRepository (5), and CatalogRepository (4). Generated serializers and Ktor bootstrap classes remain visible and are not excluded.

## Superseding latest measured heatmap — 2026-08-23 (post-Catalog/Seller batch)

Source: `build/reports/jacoco/aggregate/html/index.html` and `jacoco.xml` after the forced aggregate rerun. The HTML/XML aggregate is authoritative for the report totals.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,011 | 4,922 | 911 | 81.4913% |
| Branches | 5,962 | 8,644 | 2,682 | 68.9727% |
| Instructions | 107,827 | 148,118 | 40,291 | 72.7980% |
| Methods | 3,701 | 5,127 | 1,426 | 72.1865% |
| Classes | 875 | 1,123 | 248 | 77.9163% |

Tests: **598** declarations in **159** Kotlin test files. Catalog duplicate/unexpected persistence outcomes, Seller malformed-event handling, Promotion exact time windows, Review malformed order items, and Audit DLQ/default-actor behavior pass. The hard 100% gate remains **FAIL**.

### Top measured packages by missed branches

| Package/service | Lines missed | Branches missed | Priority |
| --- | ---: | ---: | --- |
| checkout | 37 | 209 | P0 saga/business flow |
| promotion | 31 | 168 | P0 financial |
| seller | 30 | 141 | P0 isolation/ledger |
| admin | 12 | 133 | P1 orchestration |
| identity.http | 0 | 128 | P0 security/routes |
| catalog | 19 | 121 | P1 ownership/lifecycle |
| order | 26 | 114 | P0 state machine |
| cart | 48 | 113 | P0 cart flow |
| cms | 34 | 112 | P1 lifecycle |
| inventory | 84 | 102 | P0 stock invariants |
| notification | 25 | 100 | P1 delivery lifecycle |
| wishlist | 28 | 99 | P1 application flow |

### Top measured classes by missed branches

| Rank | Service | Class | Lines missed | Branches missed |
| ---: | --- | --- | ---: | ---: |
| 1 | media | `ApplicationKt` | 10 | 23 |
| 2 | search | `ApplicationKt` | 10 | 22 |
| 3 | cart | `ApplicationKt` | 30 | 20 |
| 4 | checkout | `AddressWire` | 0 | 19 |
| 5 | payment | `ApplicationKt` | 26 | 18 |
| 6 | order | `ApplicationKt` | 25 | 18 |
| 7 | wishlist | `ApplicationKt` | 27 | 17 |
| 8 | catalog | `ApplicationKt` | 14 | 17 |
| 9 | pricing | `ApplicationKt` | 11 | 17 |
| 10 | inventory | `ApplicationKt` | 65 | 16 |

The largest generated serializer/bootstrap counts remain visible and are not excluded. The next reachable behavioral targets remain `SearchConsumer`, `CmsRepository`, `FlagRepository`, `SellerRepository`, `CatalogRepository`, `AuditRepository`, and the remaining PromotionRepository persistence lambdas.

## Latest measured heatmap — 2026-08-23

Source: `build/reports/jacoco/aggregate/jacoco.csv` after the latest unit-test and report run.

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,438 | 5,539 | 1,101 | 80.1228% |
| Branches | 5,945 | 8,636 | 2,691 | 68.8397% |
| Instructions | 107,729 | 148,077 | 40,348 | 72.7520% |
| Methods | 3,699 | 5,126 | 1,427 | 72.1615% |
| Classes | 874 | 1,123 | 249 | 77.8272% |

### Latest service/package ranking by missed branches

| Package/service | Lines missed | Branches missed | Priority |
| --- | ---: | ---: | --- |
| checkout | 58 | 209 | P0 saga/business flow |
| promotion | 37 | 170 | P0 financial |
| seller | 37 | 141 | P0 isolation/ledger |
| admin | 21 | 133 | P1 orchestration |
| identity.http | 0 | 128 | P0 security/routes |
| catalog | 25 | 121 | P1 ownership/lifecycle |
| order | 33 | 114 | P0 state machine |
| cart | 56 | 113 | P0 cart flow |
| cms | 41 | 112 | P1 lifecycle |
| inventory | 92 | 102 | P0 stock invariants |
| notification | 32 | 100 | P1 delivery lifecycle |
| payment | 36 | 98 | P0 financial |

### Latest top class concentrations

| Rank | Service | Class | Lines missed | Branches missed |
| ---: | --- | --- | ---: | ---: |
| 1 | media | `ApplicationKt` | 10 | 23 |
| 2 | search | `ApplicationKt` | 10 | 22 |
| 3 | cart | `ApplicationKt` | 30 | 20 |
| 4 | checkout | `AddressWire` | 0 | 19 |
| 5 | payment | `ApplicationKt` | 26 | 18 |
| 6 | order | `ApplicationKt` | 25 | 18 |
| 7 | wishlist | `ApplicationKt` | 27 | 17 |
| 8 | catalog | `ApplicationKt` | 14 | 17 |
| 9 | pricing | `ApplicationKt` | 11 | 17 |
| 10 | inventory | `ApplicationKt` | 65 | 16 |
| 11 | promotion | `ApplicationKt` | 27 | 16 |
| 12 | category | `ApplicationKt` | 21 | 16 |
| 13 | notification | `ApplicationKt` | 20 | 16 |
| 14 | seller | `ApplicationKt` | 17 | 16 |
| 15 | shipping | `ApplicationKt` | 28 | 15 |

The top generated serializers and compact Ktor bootstrap classes remain visible. No exclusions were added.

## Fresh clean heatmap — 2026-08-23

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 4,410 | 5,512 | 1,102 | 80.0073% |
| Branches | 5,909 | 8,638 | 2,729 | 68.4070% |
| Methods | 3,692 | 5,119 | 1,427 | 72.1235% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

### Current service heatmap ranked by missed branches

| Service | Lines missed | Branches missed | Priority |
| --- | ---: | ---: | --- |
| checkout | 58 | 209 | P0 |
| identity | 150 | 209 | P0 security |
| promotion | 37 | 170 | P0 financial |
| admin | 21 | 148 | P1 |
| seller | 37 | 141 | P0 isolation/ledger |
| catalog | 25 | 121 | P1 |
| notification | 32 | 115 | P1 |
| order | 33 | 114 | P0 state machine |
| cart | 56 | 113 | P0 |
| cms | 42 | 113 | P1 |
| inventory | 92 | 102 | P0 |
| payment | 36 | 101 | P0 financial |

### Current top class concentrations

| Service | Class | Missed lines | Missed branches |
| --- | --- | ---: | ---: |
| notification | `ApplicationKt.module.new Function2` | 4 | 25 |
| admin | `ApplicationKt.module.new Function2` | 1 | 25 |
| media | `ApplicationKt` | 10 | 23 |
| search | `ApplicationKt` | 10 | 22 |
| cart | `ApplicationKt` | 30 | 20 |
| payment | `ApplicationKt` | 26 | 18 |
| order | `ApplicationKt` | 25 | 18 |
| wishlist | `ApplicationKt` | 27 | 17 |
| catalog | `ApplicationKt` | 14 | 17 |
| pricing | `ApplicationKt` | 11 | 17 |

## Superseding fresh measurement — 2026-08-23

The latest aggregate report supersedes older tables below:

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,979 | 4,892 | 913 | 81.3369% |
| Branches | 5,907 | 8,638 | 2,731 | 68.3839% |
| Methods | 3,691 | 5,119 | 1,428 | 72.1039% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

### Fresh package heatmap ranked by missed branches

| Rank | Package/service | Lines | Branches | Missed lines | Missed branches | Priority |
| ---: | --- | ---: | ---: | ---: | ---: | --- |
| 1 | identity (all subpackages) | 1,255/1,405 | 702/911 | 150 | 209 | P0 security/application |
| 2 | checkout | 159/217 | 313/522 | 58 | 209 | P0 saga/financial |
| 3 | promotion | 185/222 | 630/800 | 37 | 170 | P0 financial |
| 4 | admin | 87/108 | 95/243 | 21 | 148 | P1 authorization |
| 5 | seller | 73/110 | 211/352 | 37 | 141 | P0 isolation/ledger |
| 6 | catalog | 142/167 | 386/507 | 25 | 121 | P1 commerce |
| 7 | notification | 108/140 | 197/312 | 32 | 115 | P1 delivery |
| 8 | order | 186/219 | 273/387 | 33 | 114 | P0 state machine |
| 9 | cms | 255/297 | 232/345 | 42 | 113 | P1 publishing |
| 10 | cart | 211/267 | 236/349 | 56 | 113 | P0 commerce |
| 11 | inventory | 150/242 | 229/331 | 92 | 102 | P0 stock invariant |
| 12 | payment | 144/180 | 208/309 | 36 | 101 | P0 financial |
| 13 | category | 161/194 | 198/295 | 33 | 97 | P1 catalog |
| 14 | search | 123/160 | 282/380 | 37 | 98 | P1 discovery |
| 15 | wishlist | 94/128 | 85/184 | 34 | 99 | P2 commerce |
| 16 | media | 72/104 | 117/211 | 32 | 94 | P1 asset integrity |
| 17 | pricing | 68/92 | 204/298 | 24 | 94 | P0 financial |
| 18 | audit | 63/78 | 130/218 | 15 | 88 | P0 compliance |
| 19 | review | 64/104 | 157/242 | 40 | 85 | P1 trust |
| 20 | flags | 55/99 | 209/285 | 44 | 76 | P1 rollout |
| 21 | shipping | 64/108 | 148/225 | 44 | 77 | P0 fulfillment |
| 22 | refund | 59/100 | 107/178 | 41 | 71 | P0 financial |
| 23 | recommendation | 41/76 | 120/188 | 35 | 68 | P2 downstream |
| 24 | analytics | 61/86 | 168/231 | 25 | 63 | P2 downstream |
| 25 | shared platform | 484/506 | 266/304 | 22 | 38 | P2 infrastructure |
| 26 | gateway | 45/72 | 4/31 | 27 | 27 | P1 edge |

The top branch count is concentrated in reachable application wiring plus high-risk identity/checkout business packages. Generated serializers and startup wiring remain open and are not excluded.

Fresh measurement from `build/reports/jacoco/aggregate/jacoco.xml` on 2026-08-23. JaCoCo is the source of truth.

## Aggregate

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,972 | 4,892 | 920 | 81.1938% |
| Branches | 5,872 | 8,638 | 2,766 | 67.9787% |
| Methods | 3,687 | 5,119 | 1,432 | 72.0258% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

## Package heatmap, ranked by missed branches

| Rank | Package/service | Lines covered/total | Branches covered/total | Missed lines | Missed branches | Priority |
| ---: | --- | ---: | ---: | ---: | ---: | --- |
| 1 | identity | 1196/1341 | 690/911 | 145 | 221 | P0 security/application |
| 2 | checkout | 126/163 | 313/522 | 37 | 209 | P0 saga/financial |
| 3 | promotion | 153/184 | 630/800 | 31 | 170 | P0 financial |
| 4 | admin | 60/72 | 95/243 | 12 | 148 | P1 authorization |
| 5 | seller | 49/79 | 208/352 | 30 | 144 | P0 isolation/ledger |
| 6 | catalog | 124/143 | 386/507 | 19 | 121 | P1 commerce |
| 7 | notification | 93/118 | 195/312 | 25 | 117 | P1 delivery |
| 8 | cms | 239/274 | 229/345 | 35 | 116 | P1 publishing |
| 9 | order | 167/193 | 272/387 | 26 | 115 | P0 state machine |
| 10 | cart | 195/244 | 235/349 | 49 | 114 | P0 commerce |
| 11 | inventory | 129/213 | 227/331 | 84 | 104 | P0 stock invariant |
| 12 | payment | 129/157 | 208/309 | 28 | 101 | P0 financial |
| 13 | wishlist | 81/109 | 85/184 | 28 | 99 | P2 commerce |
| 14 | category | 146/173 | 196/295 | 27 | 99 | P1 catalog |
| 15 | search | 113/143 | 282/380 | 30 | 98 | P1 discovery |
| 16 | media | 59/85 | 117/211 | 26 | 94 | P1 asset integrity |
| 17 | pricing | 57/73 | 204/298 | 16 | 94 | P0 financial |
| 18 | audit | 60/65 | 130/218 | 5 | 88 | P0 compliance |
| 19 | review | 49/83 | 157/242 | 34 | 85 | P1 trust |
| 20 | flags | 44/82 | 206/285 | 38 | 79 | P1 rollout |
| 21 | shipping | 51/87 | 148/225 | 36 | 77 | P0 fulfillment |
| 22 | refund | 46/82 | 103/178 | 36 | 75 | P0 financial |
| 23 | recommendation | 35/64 | 118/188 | 29 | 70 | P2 downstream |
| 24 | analytics | 56/75 | 168/231 | 19 | 63 | P2 downstream |
| 25 | gateway | 43/66 | 4/31 | 23 | 27 | P1 edge |
| 26 | shared platform | 472/524 | 266/304 | 52 | 38 | P2 infrastructure |

## Top branch concentrations

| Rank | Class | Missed lines | Missed branches | Missing path category |
| ---: | --- | ---: | ---: | --- |
| 1 | `notification.ApplicationKt$module$1` | 4 | 25 | consumer callback outcomes |
| 2 | `admin.ApplicationKt$module$1` | 1 | 25 | downstream orchestration outcomes |
| 3 | `media.ApplicationKt` | 10 | 23 | configuration and route guards |
| 4 | `search.ApplicationKt` | 10 | 22 | configuration and route alternatives |
| 5 | `cart.ApplicationKt` | 30 | 20 | route/configuration guards |
| 6 | `inventory.ApplicationKt` | 65 | 18 | reservation/authorization routes |
| 7 | `payment.ApplicationKt` | 26 | 18 | payment route/state guards |
| 8 | `order.ApplicationKt` | 25 | 18 | route/configuration guards |
| 9 | `seller.ApplicationKt` | 17 | 18 | ownership/downstream guards |
| 10 | `wishlist.ApplicationKt` | 27 | 17 | route/configuration guards |
| 11 | `catalog.ApplicationKt` | 14 | 17 | cache/permission guards |
| 12 | `pricing.ApplicationKt` | 11 | 17 | route/configuration guards |
| 13 | `promotion.ApplicationKt` | 27 | 16 | route/configuration guards |
| 14 | `category.ApplicationKt` | 21 | 16 | route/configuration guards |
| 15 | `shipping.ApplicationKt` | 28 | 15 | provider/route guards |
| 16 | `refund.ApplicationKt` | 27 | 15 | financial route guards |
| 17 | `notification.ApplicationKt` | 19 | 15 | delivery route guards |
| 18 | `checkout.ApplicationKt` | 8 | 15 | saga route/configuration |

The next branch-first work should target the highest-impact reachable business/application branches beneath these packages. Generated/private serializer markers and startup wiring are tracked separately and are not treated as covered merely because a nearby route test passes. Phase 10A remains **NO-GO**.

## Fresh authoritative snapshot (2026-08-25)

Generated from `build/reports/jacoco/aggregate/jacoco.xml` after the latest full test/report run:

| Package | Lines | Branches | Missed lines | Missed branches | Priority |
| --- | ---: | ---: | ---: | ---: | --- |
| promotion | 156/187 | 667/800 | 31 | 133 | P0 financial |
| admin | 74/86 | 111/239 | 12 | 128 | P1 authorization/orchestration |
| seller | 49/79 | 230/356 | 30 | 126 | P0 ownership/ledger |
| checkout | 126/163 | 407/522 | 37 | 115 | P0 saga |
| identity/http | 205/205 | 278/392 | 0 | 114 | P0 security/routes |
| cart | 198/244 | 243/349 | 46 | 106 | P0 commerce |
| order | 167/193 | 287/387 | 26 | 100 | P0 state/financial |
| cms | 240/274 | 246/345 | 34 | 99 | P1 workflow |
| payment | 129/157 | 211/309 | 28 | 98 | P0 financial |
| inventory | 129/213 | 236/331 | 84 | 95 | P0 stock |
| notification | 106/131 | 220/312 | 25 | 92 | P1 delivery |
| catalog | 124/143 | 415/507 | 19 | 92 | P1 catalog/ownership |
| category | 146/173 | 206/295 | 27 | 89 | P1 catalog |
| search | 114/143 | 296/380 | 29 | 84 | P1 search |
| media | 86/97 | 129/211 | 11 | 82 | P1 storage |

Aggregate: **4,064/4,953 lines (82.0109%)** and **6,317/8,636 branches (73.1478%)**. The exact aggregate counters are authoritative; this phase remains **NO-GO**.
