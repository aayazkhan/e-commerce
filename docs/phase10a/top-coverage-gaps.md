# Phase 10A top coverage gaps

## Latest authoritative top-20 — 2026-08-24

Source: `build/reports/jacoco/aggregate/jacoco.xml`. Totals: **4,059/4,954 lines**, **6,289/8,644 branches**, **895 missed lines**, and **2,355 missed branches**. Tests: **631** in **164** Kotlin test files.

| Rank | Service | Class | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | media | `ApplicationKt` | 10 | 23 | P1 bootstrap/routes |
| 2 | search | `ApplicationKt` | 10 | 22 | P1 bootstrap/routes |
| 3 | cart | `ApplicationKt` | 30 | 20 | P0 cart flow |
| 4 | payment | `ApplicationKt` | 26 | 18 | P0 payment flow |
| 5 | order | `ApplicationKt` | 25 | 18 | P0 order flow |
| 6 | wishlist | `ApplicationKt` | 27 | 17 | P1 wishlist flow |
| 7 | catalog | `ApplicationKt` | 14 | 17 | P1 catalog flow |
| 8 | pricing | `ApplicationKt` | 11 | 17 | P0 pricing flow |
| 9 | inventory | `ApplicationKt` | 65 | 16 | P0 inventory flow |
| 10 | promotion | `ApplicationKt` | 27 | 16 | P0 financial flow |
| 11 | category | `ApplicationKt` | 21 | 16 | P1 lifecycle |
| 12 | notification | `ApplicationKt` | 20 | 16 | P1 delivery |
| 13 | seller | `ApplicationKt` | 17 | 16 | P0 seller isolation |
| 14 | shipping | `ApplicationKt` | 28 | 15 | P0 provider flow |
| 15 | refund | `ApplicationKt` | 27 | 15 | P0 financial flow |
| 16 | checkout | `ApplicationKt` | 8 | 15 | P0 Saga |
| 17 | search | `SearchIndexClient` | 0 | 15 | P1 adapter decisions |
| 18 | analytics | `ApplicationKt$module$7$3` | 1 | 13 | P1 consumer bootstrap |
| 19 | audit | `ApplicationKt$module$7$3` | 1 | 13 | P1 consumer bootstrap |
| 20 | notification | `ApplicationKt$module$8$3` | 1 | 13 | P1 consumer bootstrap |

Reachable non-bootstrap hotspots remain `SearchConsumer.apply` (10), `PromotionRepository` (9), `ReviewRepository` (9), `IdentityService` (8), and `SellerRepository` (7). These are not being treated as complete merely because their line counters are covered.

## Superseding current top-20 inventory — 2026-08-24 (shared-boundary and identity-route batch)

Source: `build/reports/jacoco/aggregate/jacoco.xml`. Totals: **4,055/4,954 lines (81.8530%)**, **5,973/8,644 branches (69.1000%)**, **899 missed lines**, and **2,671 missed branches**. Tests: **613** in **162** Kotlin test files.

| Rank | Service | Class/function | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | media | `ApplicationKt` | 10 | 23 | P1 bootstrap/routes |
| 2 | search | `ApplicationKt` | 10 | 22 | P1 bootstrap/routes |
| 3 | cart | `ApplicationKt` | 30 | 20 | P0 cart flow |
| 4 | checkout | `AddressWire` | 0 | 19 | P1 generated contract |
| 5 | payment | `ApplicationKt` | 26 | 18 | P0 payment flow |
| 6 | order | `ApplicationKt` | 25 | 18 | P0 order flow |
| 7 | wishlist | `ApplicationKt` | 27 | 17 | P1 wishlist flow |
| 8 | catalog | `ApplicationKt` | 14 | 17 | P1 catalog flow |
| 9 | pricing | `ApplicationKt` | 11 | 17 | P0 pricing flow |
| 10 | inventory | `ApplicationKt` | 65 | 16 | P0 inventory flow |
| 11 | promotion | `ApplicationKt` | 27 | 16 | P0 promotion flow |
| 12 | category | `ApplicationKt` | 21 | 16 | P1 category flow |
| 13 | notification | `ApplicationKt` | 20 | 16 | P1 delivery flow |
| 14 | seller | `ApplicationKt` | 17 | 16 | P0 seller isolation |
| 15 | shipping | `ApplicationKt` | 28 | 15 | P0 provider flow |
| 16 | refund | `ApplicationKt` | 27 | 15 | P0 financial flow |
| 17 | checkout | `ApplicationKt` | 8 | 15 | P0 Saga flow |
| 18 | search | `SearchIndexClient` | 0 | 15 | P1 adapter boundaries |
| 19 | wishlist | `WishlistEvent` | 0 | 14 | P1 generated/event contract |
| 20 | platform.security | `AccessClaims` | 2 | 13 | P0 security/claims |

The raw ranking is dominated by application bootstrap and generated contract classes. Reachable business classes remain separately tracked in `remaining-branch-inventory.md` so test work is not redirected to duplicate already-covered happy paths.

## Superseding current top-20 inventory — 2026-08-24 (catalog contract batch)

Source: `build/reports/jacoco/aggregate/jacoco.xml`. Totals: **4,023/4,922 lines (81.7351%)**, **5,968/8,644 branches (69.0421%)**, **899 missed lines**, and **2,676 missed branches**. Tests: **606** in **160** Kotlin test files.

The sparse `Product` contract test reduced its generated serializer from 11 to 9 missed branches. The remaining raw top classes are dominated by Ktor bootstrap and serialization-generated paths; reachable business code remains tracked separately below.

| Rank | Service | Class/function | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | media | `ApplicationKt` | 10 | 23 | P1 bootstrap/routes |
| 2 | search | `ApplicationKt` | 10 | 22 | P1 bootstrap/routes |
| 3 | cart | `ApplicationKt` | 30 | 20 | P0 cart flow |
| 4 | checkout | `AddressWire` serializer | 0 | 19 | P1 generated contract |
| 5 | payment | `ApplicationKt` | 26 | 18 | P0 payment flow |
| 6 | order | `ApplicationKt` | 25 | 18 | P0 order flow |
| 7 | wishlist | `ApplicationKt` | 27 | 17 | P1 wishlist flow |
| 8 | catalog | `ApplicationKt` | 14 | 17 | P1 catalog flow |
| 9 | pricing | `ApplicationKt` | 11 | 17 | P0 pricing flow |
| 10 | inventory | `ApplicationKt` | 65 | 16 | P0 inventory flow |
| 11 | promotion | `ApplicationKt` | 27 | 16 | P0 promotion flow |
| 12 | category | `ApplicationKt` | 21 | 16 | P1 category flow |
| 13 | notification | `ApplicationKt` | 20 | 16 | P1 delivery flow |
| 14 | seller | `ApplicationKt` | 17 | 16 | P0 seller isolation |
| 15 | shipping | `ApplicationKt` | 28 | 15 | P0 shipping flow |
| 16 | refund | `ApplicationKt` | 27 | 15 | P0 refund flow |
| 17 | checkout | `ApplicationKt` | 8 | 15 | P0 saga flow |
| 18 | search | `SearchIndexClient` | 0 | 15 | P1 adapter decisions |
| 19 | wishlist | `WishlistEvent` serializer | 0 | 14 | P1 generated contract |
| 20 | security | `AccessClaims` serializer | 2 | 13 | P0 token contract |

## Superseding latest top-20 inventory — 2026-08-24

Source: `build/reports/jacoco/aggregate/jacoco.xml` after the forced aggregate rerun. Totals: **4,023/4,922 lines (81.7351%)**, **5,966/8,644 branches (69.0190%)**, **899 missed lines**, and **2,678 missed branches**. Tests: **604** in **160** Kotlin test files.

| Rank | Service | Class/function | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | checkout | `AddressWire` serializer | 0 | 19 | P1 contract/default paths; generated |
| 2 | search | `SearchIndexClient` | 0 | 15 | P1 adapter decisions |
| 3 | wishlist | `WishlistEvent` serializer | 0 | 14 | P1 event contract/defaults; generated |
| 4 | security | `AccessClaims` serializer | 2 | 13 | P0 token contract |
| 5 | checkout | `PromotionQuoteWire` serializer | 0 | 13 | P0 contract/default paths; generated |
| 6 | checkout | `ProductVariantWire` serializer | 0 | 13 | P0 contract/default paths; generated |
| 7 | pricing | `QuoteRequest` serializer | 0 | 13 | P0 pricing contract; generated |
| 8 | search | `SearchConsumer.apply` | 0 | 10 | P1 event dispatch |
| 9 | promotion | `PromotionRequest` serializer | 0 | 11 | P0 promotion contract; generated |
| 10 | catalog | `Product` serializer | 0 | 11 | P1 catalog contract; generated |
| 11 | checkout | `PriceRequest` serializer | 0 | 11 | P0 pricing contract; generated |
| 12 | audit | `AuditRequest` serializer | 0 | 11 | P1 audit contract; generated |
| 13 | promotion | `PromotionRepository` | 0 | 11 | P0 eligibility/persistence |
| 14 | cms | `SeoMetadata` serializer | 0 | 10 | P1 CMS contract; generated |
| 15 | notification | `PreferenceRequest` serializer | 0 | 9 | P1 preference contract; generated |
| 16 | review | `ReviewRepository` | 0 | 9 | P1 ingestion/moderation |
| 17 | seller | `LedgerEntryRequest` serializer | 0 | 9 | P0 ledger contract; generated |
| 18 | category | `ReorderRequest` serializer | 0 | 8 | P1 category contract; generated |
| 19 | checkout | `ProductWire` serializer | 0 | 8 | P0 catalog contract; generated |
| 20 | flags | `EvaluationRequest` serializer | 0 | 8 | P1 targeting contract; generated |

The raw top-20 list is dominated by serialization-generated branches. The next reachable business tests are therefore selected from `SearchConsumer`, `PromotionRepository`, `ReviewRepository`, and the seller/catalog/CMS/flag repositories only where JaCoCo identifies an input or state that is not already covered. No reflection-only or hash-collision tests are used.

## Superseding post-Catalog/Seller ranking — 2026-08-23

Source: `build/reports/jacoco/aggregate/jacoco.xml` after the forced aggregate rerun. The authoritative totals are **4,011/4,922 lines (81.4913%)** and **5,962/8,644 branches (68.9727%)**, leaving **911 missed lines** and **2,682 missed branches**. Tests: **598** in **159** Kotlin test files.

The largest class counts are dominated by Ktor bootstrap/generated serializers. The largest reachable non-generated classes/functions are listed separately so business tests are not obscured by wiring artifacts.

| Rank | Service | Class/function | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | search | `SearchIndexClient` | 0 | 15 | P1 search adapter decisions |
| 2 | wishlist | `WishlistEvent` serializer | 0 | 14 | P1 event contract/defaults |
| 3 | security | `AccessClaims` serializer | 2 | 13 | P0 token contract |
| 4 | pricing | `QuoteRequest` serializer | 0 | 13 | P0 pricing contract |
| 5 | search | `SearchConsumer` | 2 | 12 | P1 event dispatch |
| 6 | promotion | `PromotionRequest` serializer | 0 | 11 | P0 promotion contract |
| 7 | audit | `AuditRequest` serializer | 0 | 10 | P1 audit contract |
| 8 | cms | `SeoMetadata` serializer | 0 | 10 | P1 CMS contract |
| 9 | notification | `PreferenceRequest` serializer | 0 | 9 | P1 preference contract |
| 10 | review | `ReviewRepository` | 0 | 9 | P1 ingestion/moderation |
| 11 | seller | `SellerRepository` | 1 | 7 | P0 seller isolation/events |
| 12 | cms | `CmsRepository` | 0 | 7 | P1 lifecycle persistence |
| 13 | flags | `FlagRepository` | 0 | 7 | P1 deterministic targeting |
| 14 | catalog | `CatalogRepository` | 0 | 7 | P1 lifecycle persistence |
| 15 | order | `OrderRepository` | 0 | 7 | P0 state/outbox |

The Catalog batch passed duplicate-constraint and unexpected-failure assertions. The next reachable behavioral focus is SearchIndexClient/SearchConsumer only where an actual untested input exists; Kotlin dispatch collision edges and generated serializer branches are not being covered with artificial inputs.

## Superseding latest ranking — 2026-08-23

Source: `build/reports/jacoco/aggregate/html/index.html` and `jacoco.xml`. Aggregate: **4,011/4,922 lines (81.4913%)**, **5,947/8,636 branches (68.8629%)**, **911 missed lines**, and **2,689 missed branches**. Tests: **598** in **159** Kotlin test files.

| Rank | Service | Class/function | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | media | `ApplicationKt` | 10 | 23 | P1 route/config wiring |
| 2 | search | `ApplicationKt` | 10 | 22 | P1 route/config wiring |
| 3 | cart | `ApplicationKt` | 30 | 20 | P0 route/application flow |
| 4 | checkout | `AddressWire` | 0 | 19 | generated serializer; contract already exercised |
| 5 | payment | `ApplicationKt` | 26 | 18 | P0 application/state flow |
| 6 | order | `ApplicationKt` | 25 | 18 | P0 state flow |
| 7 | wishlist | `ApplicationKt` | 27 | 17 | P1 application flow |
| 8 | catalog | `ApplicationKt` | 14 | 17 | P1 lifecycle/ownership |
| 9 | pricing | `ApplicationKt` | 11 | 17 | P0 financial flow |
| 10 | inventory | `ApplicationKt` | 65 | 16 | P0 stock flow |
| 11 | promotion | `ApplicationKt` | 27 | 16 | P0 financial flow |
| 12 | category | `ApplicationKt` | 21 | 16 | P1 lifecycle |
| 13 | notification | `ApplicationKt` | 20 | 16 | P1 delivery flow |
| 14 | seller | `ApplicationKt` | 17 | 16 | P0 isolation/ledger |
| 15 | shipping | `ApplicationKt` | 28 | 15 | P1 provider/application flow |

Reachable non-generated follow-ups measured separately include `SearchIndexClient` (15 missed branches), `SearchConsumer` (12), `PromotionRepository` (11), `CmsRepository` (8), `FlagRepository` (8), `SellerRepository` (7), `CatalogRepository` (7), and `AuditRepository` (7). Generated serializers and startup wiring remain open and unexcluded.

## Latest measured ranking — 2026-08-23

Source: `build/reports/jacoco/aggregate/jacoco.csv`. Current aggregate is **4,438/5,539 lines (80.1228%)** and **5,945/8,636 branches (68.8397%)**, with **1,101 missed lines** and **2,691 missed branches**. Current tests: **597** in **159** Kotlin test files.

| Rank | Service | Class/function | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | media | `ApplicationKt` | 10 | 23 | P1 route/config wiring |
| 2 | search | `ApplicationKt` | 10 | 22 | P1 route/config wiring |
| 3 | cart | `ApplicationKt` | 30 | 20 | P0 route/application flow |
| 4 | checkout | `AddressWire` | 0 | 19 | generated serializer; contract already exercised |
| 5 | payment | `ApplicationKt` | 26 | 18 | P0 application/state flow |
| 6 | order | `ApplicationKt` | 25 | 18 | P0 state flow |
| 7 | wishlist | `ApplicationKt` | 27 | 17 | P1 application flow |
| 8 | catalog | `ApplicationKt` | 14 | 17 | P1 lifecycle/ownership |
| 9 | pricing | `ApplicationKt` | 11 | 17 | P0 financial flow |
| 10 | inventory | `ApplicationKt` | 65 | 16 | P0 stock flow |
| 11 | promotion | `ApplicationKt` | 27 | 16 | P0 financial flow |
| 12 | category | `ApplicationKt` | 21 | 16 | P1 lifecycle |
| 13 | notification | `ApplicationKt` | 20 | 16 | P1 delivery flow |
| 14 | seller | `ApplicationKt` | 17 | 16 | P0 isolation/ledger |
| 15 | shipping | `ApplicationKt` | 28 | 15 | P1 provider/application flow |

Reachable non-generated follow-ups remain `PromotionRepository` (12 missed branches after the nullable-rate tests), `SearchIndexClient` (15), `SearchConsumer` (12), `CmsRepository` (7), `FlagRepository` (7), `SellerRepository` (7), `CatalogRepository` (7), and `AuditRepository` (7). The CMS `validTransition` branch is now complete; remaining CMS misses are in persistence lambdas.

## Fresh clean ranking — 2026-08-23

Current aggregate: **4,410/5,512 lines (80.0073%)** and **5,909/8,638 branches (68.4070%)**. Missed: **1,102 lines** and **2,729 branches**. Tests: **582** in **157** Kotlin test files.

| Rank | Service | Class | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | notification | `ApplicationKt.module.new Function2` | 4 | 25 | P1 delivery wiring |
| 2 | admin | `ApplicationKt.module.new Function2` | 1 | 25 | P1 orchestration wiring |
| 3 | media | `ApplicationKt` | 10 | 23 | P1 media routes/config |
| 4 | search | `ApplicationKt` | 10 | 22 | P1 search routes/config |
| 5 | cart | `ApplicationKt` | 30 | 20 | P0 cart routes/config |
| 6 | checkout | `AddressWire` | 0 | 19 | generated serialization paths; public contract already tested |
| 7 | payment | `ApplicationKt` | 26 | 18 | P0 payment routes/config |
| 8 | order | `ApplicationKt` | 25 | 18 | P0 order routes/config |
| 9 | wishlist | `ApplicationKt` | 27 | 17 | P1 wishlist routes/config |
| 10 | catalog | `ApplicationKt` | 14 | 17 | P1 catalog routes/config |
| 11 | pricing | `ApplicationKt` | 11 | 17 | P0 pricing routes/config |
| 12 | inventory | `ApplicationKt` | 65 | 16 | P0 inventory routes/config |
| 13 | promotion | `ApplicationKt` | 27 | 16 | P0 promotion routes/config |
| 14 | category | `ApplicationKt` | 21 | 16 | P1 category routes/config |
| 15 | seller | `ApplicationKt` | 17 | 16 | P0 seller isolation/ledger |

Non-generated branch concentrations requiring behavioral review include `SearchIndexClient` (15), `PromotionRepository` (13), `SearchConsumer` (12), `CmsRepository` (8), `FlagRepository` (8), `SellerRepository` (7), `CatalogRepository` (7), `AuditRepository` (7), and `OrderRepository` (7). Existing tests were checked before adding the Kafka shutdown-resilience regression; no duplicate broad provider tests were added.

## Superseding fresh ranking — 2026-08-23

Source: `build/reports/jacoco/aggregate/jacoco.csv` and `jacoco.xml` after the latest clean test/report run.

Aggregate: **3,979/4,892 lines (81.3369%)**, **5,907/8,638 branches (68.3839%)**, **913 missed lines**, **2,731 missed branches**.

### Top 20 classes by missed branches

| Rank | Service | Class | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | notification | `ApplicationKt.module.new Function2` | 4 | 25 | P1 delivery wiring |
| 2 | admin | `ApplicationKt.module.new Function2` | 1 | 25 | P1 orchestration wiring |
| 3 | media | `ApplicationKt` | 10 | 23 | P1 media routes/config |
| 4 | search | `ApplicationKt` | 10 | 22 | P1 search routes/config |
| 5 | cart | `ApplicationKt` | 30 | 20 | P0 cart routes/config |
| 6 | checkout | `AddressWire` | 0 | 19 | P0 checkout serialization |
| 7 | payment | `ApplicationKt` | 26 | 18 | P0 payment routes/config |
| 8 | order | `ApplicationKt` | 25 | 18 | P0 order routes/config |
| 9 | wishlist | `ApplicationKt` | 27 | 17 | P2 wishlist routes/config |
| 10 | catalog | `ApplicationKt` | 14 | 17 | P1 catalog routes/config |
| 11 | pricing | `ApplicationKt` | 11 | 17 | P0 pricing routes/config |
| 12 | inventory | `ApplicationKt` | 65 | 16 | P0 inventory routes/config |
| 13 | promotion | `ApplicationKt` | 27 | 16 | P0 promotion routes/config |
| 14 | category | `ApplicationKt` | 21 | 16 | P1 category routes/config |
| 15 | seller | `ApplicationKt` | 17 | 16 | P0 seller isolation/config |
| 16 | shipping | `ApplicationKt` | 28 | 15 | P0 shipping routes/config |
| 17 | search | `SearchIndexClient` | 0 | 15 | P1 search client boundary |
| 18 | refund | `ApplicationKt` | 27 | 15 | P0 refund routes/config |
| 19 | notification | `ApplicationKt` | 19 | 15 | P1 notification routes/config |
| 20 | checkout | `ApplicationKt` | 8 | 15 | P0 checkout routes/config |

The highest reachable non-wiring classes remain `PromotionRepository` (13), `SearchConsumer` (13), `CmsRepository` (11), `FlagRepository` (8 after the latest batch), `ReviewRepository` (9), `IdentityService` (8), `HttpPaymentProvider` (9), `SellerRepository` (7), `RefundRepository` (7), and `RecommendationRepository` (remaining branch paths). The next continuation should inspect those exact methods before adding more generic route tests.

Fresh measurement from `build/reports/jacoco/aggregate/jacoco.xml` on 2026-08-23. JaCoCo is the source of truth.

## Aggregate

| Counter | Covered | Total | Missed | Coverage |
| --- | ---: | ---: | ---: | ---: |
| Lines | 3,972 | 4,892 | 920 | 81.1938% |
| Branches | 5,872 | 8,638 | 2,766 | 67.9787% |
| Methods | 3,687 | 5,119 | 1,432 | 72.0258% |
| Classes | 873 | 1,122 | 249 | 77.8075% |

## Service ranking

| Service | Missed lines | Missed branches | Branch coverage | Priority |
| --- | ---: | ---: | ---: | --- |
| identity | 145 | 221 | 75.74% | P0 security/application |
| checkout | 37 | 209 | 59.96% | P0 saga/financial |
| promotion | 31 | 170 | 78.75% | P0 financial |
| admin | 12 | 148 | 39.09% | P1 authorization |
| seller | 30 | 144 | 59.09% | P0 isolation/ledger |
| catalog | 19 | 121 | 76.13% | P1 commerce |
| notification | 25 | 117 | 62.50% | P1 delivery |
| cms | 35 | 116 | 66.38% | P1 publishing |
| order | 26 | 115 | 70.28% | P0 state machine |
| cart | 49 | 114 | 67.34% | P0 commerce |
| inventory | 84 | 104 | 68.58% | P0 stock invariant |
| payment | 28 | 101 | 67.31% | P0 financial |
| wishlist | 28 | 99 | 46.20% | P2 commerce |
| category | 27 | 99 | 66.44% | P1 catalog |
| search | 30 | 98 | 74.21% | P1 discovery |
| media | 26 | 94 | 55.45% | P1 asset integrity |
| pricing | 16 | 94 | 68.46% | P0 financial |
| audit | 5 | 88 | 59.63% | P0 compliance |
| review | 34 | 85 | 64.88% | P1 trust |
| flags | 38 | 79 | 72.28% | P1 rollout |
| shipping | 36 | 77 | 65.78% | P0 fulfillment |
| refund | 36 | 75 | 57.87% | P0 financial |
| recommendation | 29 | 70 | 62.77% | P2 downstream |
| analytics | 19 | 63 | 72.73% | P2 downstream |
| shared platform | 52 | 38 | 87.50% | P2 infrastructure |
| gateway | 23 | 27 | 12.90% | P1 edge |

## Top 20 class gaps

| Rank | Service/package | Class | Missed lines | Missed branches | Priority |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | notification | `ApplicationKt$module$1` | 4 | 25 | P1 consumer delivery |
| 2 | admin | `ApplicationKt$module$1` | 1 | 25 | P1 downstream orchestration |
| 3 | media | `ApplicationKt` | 10 | 23 | P1 asset route/configuration |
| 4 | search | `ApplicationKt` | 10 | 22 | P1 route/configuration |
| 5 | cart | `ApplicationKt` | 30 | 20 | P0 commerce route/configuration |
| 6 | inventory | `ApplicationKt` | 65 | 18 | P0 stock route/configuration |
| 7 | payment | `ApplicationKt` | 26 | 18 | P0 payment route/configuration |
| 8 | order | `ApplicationKt` | 25 | 18 | P0 order route/configuration |
| 9 | seller | `ApplicationKt` | 17 | 18 | P0 isolation/downstream |
| 10 | wishlist | `ApplicationKt` | 27 | 17 | P2 commerce route/configuration |
| 11 | catalog | `ApplicationKt` | 14 | 17 | P1 cache/permission guards |
| 12 | pricing | `ApplicationKt` | 11 | 17 | P0 financial route/configuration |
| 13 | promotion | `ApplicationKt` | 27 | 16 | P0 promotion route/configuration |
| 14 | category | `ApplicationKt` | 21 | 16 | P1 catalog route/configuration |
| 15 | shipping | `ApplicationKt` | 28 | 15 | P0 fulfillment route/configuration |
| 8 | inventory | `ApplicationKt` | 65 | 18 | P0 stock route/configuration |
| 9 | payment | `ApplicationKt` | 26 | 18 | P0 payment route/configuration |
| 10 | order | `ApplicationKt` | 25 | 18 | P0 order route/configuration |
| 11 | seller | `ApplicationKt` | 17 | 18 | P0 isolation/downstream |
| 12 | wishlist | `ApplicationKt` | 27 | 17 | P2 commerce route/configuration |
| 13 | catalog | `ApplicationKt` | 14 | 17 | P1 cache/permission guards |
| 14 | pricing | `ApplicationKt` | 11 | 17 | P0 financial route/configuration |
| 15 | promotion | `ApplicationKt` | 27 | 16 | P0 promotion route/configuration |
| 16 | category | `ApplicationKt` | 21 | 16 | P1 catalog route/configuration |
| 17 | shipping | `ApplicationKt` | 28 | 15 | P0 fulfillment route/configuration |
| 18 | refund | `ApplicationKt` | 27 | 15 | P0 refund route/configuration |
| 19 | notification | `ApplicationKt` | 19 | 15 | P1 notification route/configuration |
| 20 | checkout | `ApplicationKt` | 8 | 15 | P0 checkout route/configuration |

The current top classes are dominated by application wiring and generated/private wire serializers; behavioral targets remain the highest-risk repository/application methods underneath those packages. Phase 10A remains **NO-GO** until the aggregate counters are zero and the unchanged hard JaCoCo verification task passes.

## Fresh top-branch inventory (2026-08-25)

The current XML ranks compiler-generated coroutine entry methods first (13 missed branches each in analytics, audit, notification, recommendation, and review application startup wrappers). The largest non-startup behavioral classes are:

| Rank | Package | Class | Method | Missed lines | Missed branches |
| ---: | --- | --- | --- | ---: | ---: |
| 1 | order | `OrderRepository` | `outbox` | 0 | 6 |
| 2 | cms | `CmsRepository` | `transition$lambda$0` | 0 | 3 |
| 3 | promotion | `PromotionRepository` | `transition$lambda$0` | 0 | 3 |
| 4 | seller | `SellerRepository` | `apply$lambda$0` | 0 | 3 |
| 5 | cart | `CartRepository` | `update$lambda$0` | 0 | 2 |
| 6 | cms | `CmsRepository` | `update$lambda$0` | 0 | 2 |
| 7 | inventory | `InventoryRepository` | `transition$lambda$0` | 0 | 2 |
| 8 | payment | `HttpPaymentProvider` | `toProviderPayment` | 0 | 2 |
| 9 | payment | `HttpPaymentProvider` | `call` | 0 | 2 |
| 10 | promotion | `PromotionRepository` | `calculate` | 0 | 2 |
| 11 | seller | `SellerRepository` | `deadLetter` | 0 | 2 |
| 12 | seller | `SellerRepository` | `transition$lambda$0` | 0 | 2 |

The remaining shared-module misses are serialization-generated constructor branches in `Money`, `CursorPage`, `CursorPageRequest`, `ApiError`, and `FieldViolation`; Kafka's remaining reported entry branch is the compiler-generated coroutine launch entry. No exclusions were added.
