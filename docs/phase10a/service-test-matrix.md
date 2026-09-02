# Phase 10A service test matrix

## Latest authoritative aggregate — 2026-08-24

Measured from `build/reports/jacoco/aggregate/jacoco.xml`: **4,059/4,954 lines**, **6,289/8,644 branches**, **895** missed lines, and **2,355** missed branches. Tests: **631** across **164** Kotlin test files. Unit/report: **PASS**; hard gate: **FAIL**. Every service remains **INCOMPLETE** until its measured meaningful production line and branch counters reach 100%.

| Service/module | Missed lines | Missed branches | Status |
| --- | ---: | ---: | --- |
| promotion | 31 | 133 | INCOMPLETE |
| admin | 12 | 128 | INCOMPLETE |
| seller | 30 | 126 | INCOMPLETE |
| checkout | 37 | 115 | INCOMPLETE |
| identity.http | 0 | 114 | INCOMPLETE |
| cart | 46 | 108 | INCOMPLETE |
| order | 26 | 100 | INCOMPLETE |
| cms | 34 | 99 | INCOMPLETE |
| payment | 28 | 98 | INCOMPLETE |
| inventory | 84 | 95 | INCOMPLETE |
| search | 29 | 95 | INCOMPLETE |
| notification | 25 | 92 | INCOMPLETE |
| catalog | 19 | 92 | INCOMPLETE |
| category | 27 | 91 | INCOMPLETE |
| media | 11 | 87 | INCOMPLETE |
| review | 34 | 85 | INCOMPLETE |
| wishlist | 28 | 81 | INCOMPLETE |
| pricing | 16 | 79 | INCOMPLETE |
| shipping | 36 | 76 | INCOMPLETE |
| audit | 5 | 74 | INCOMPLETE |
| refund | 34 | 71 | INCOMPLETE |
| recommendation | 29 | 64 | INCOMPLETE |
| flags | 38 | 61 | INCOMPLETE |
| analytics | 19 | 58 | INCOMPLETE |
| identity | 70 | 38 | INCOMPLETE |
| gateway | 23 | 27 | INCOMPLETE |
| shared/platform | 51 | 10 | INCOMPLETE |

## Superseding latest aggregate matrix — 2026-08-24 (shared-boundary and identity-route batch)

Measured from the authoritative aggregate XML/HTML report. Every service remains incomplete until its meaningful production line and branch counters reach 100%.

Aggregate: **4,055/4,954 lines**, **5,973/8,644 branches**, **899** missed lines, **2,671** missed branches. Unit tests/report: **PASS**; hard gate: **FAIL**.

| Service/module | Lines missed | Branches missed | Status |
| --- | ---: | ---: | --- |
| checkout | 37 | 209 | INCOMPLETE |
| promotion | 31 | 168 | INCOMPLETE |
| seller | 30 | 141 | INCOMPLETE |
| admin | 12 | 133 | INCOMPLETE |
| identity.http | 0 | 125 | INCOMPLETE |
| catalog | 19 | 116 | INCOMPLETE |
| order | 26 | 114 | INCOMPLETE |
| cart | 48 | 113 | INCOMPLETE |
| cms | 34 | 112 | INCOMPLETE |
| inventory | 84 | 102 | INCOMPLETE |
| notification | 25 | 100 | INCOMPLETE |
| wishlist | 28 | 99 | INCOMPLETE |
| payment | 28 | 98 | INCOMPLETE |
| category | 27 | 97 | INCOMPLETE |
| search | 29 | 95 | INCOMPLETE |
| pricing | 16 | 94 | INCOMPLETE |
| media | 11 | 92 | INCOMPLETE |
| review | 34 | 85 | INCOMPLETE |
| audit | 5 | 84 | INCOMPLETE |
| shipping | 36 | 76 | INCOMPLETE |
| flags | 38 | 73 | INCOMPLETE |
| refund | 34 | 71 | INCOMPLETE |
| recommendation | 29 | 68 | INCOMPLETE |
| analytics | 19 | 63 | INCOMPLETE |
| identity | 70 | 38 | INCOMPLETE |
| shared/platform | 51 | 10 | INCOMPLETE |

The shared module gate remains blocked by generated/defensive branch edges in common, error-handling, and kafka; no build rule was weakened.

## Superseding latest aggregate matrix — 2026-08-24 (catalog contract batch)

Measured from the authoritative aggregate XML/HTML report. Every service remains incomplete until its meaningful production line and branch counters reach 100%.

Aggregate: **4,023/4,922 lines**, **5,968/8,644 branches**, **899** missed lines, **2,676** missed branches. Unit tests/report: **PASS**; hard gate: **FAIL**.

The catalog package is now **19** missed lines and **116** missed branches after the sparse `Product` contract test. Other package values are unchanged from the preceding checkpoint.

## Superseding latest aggregate matrix — 2026-08-24 (media/search/shared batch)

Measured from the authoritative aggregate XML/HTML report. Every service remains incomplete until its meaningful production line and branch counters reach 100%.

Aggregate: **4,023/4,922 lines**, **5,966/8,644 branches**, **899** missed lines, **2,678** missed branches. Unit tests/report: **PASS**; hard gate: **FAIL**.

| Service/module | Lines missed | Branches missed | Status |
| --- | ---: | ---: | --- |
| checkout | 37 | 209 | INCOMPLETE |
| promotion | 31 | 168 | INCOMPLETE |
| seller | 30 | 141 | INCOMPLETE |
| admin | 12 | 133 | INCOMPLETE |
| identity.http | 0 | 128 | INCOMPLETE |
| catalog | 19 | 118 | INCOMPLETE |
| order | 26 | 114 | INCOMPLETE |
| cart | 48 | 113 | INCOMPLETE |
| cms | 34 | 112 | INCOMPLETE |
| inventory | 84 | 102 | INCOMPLETE |
| notification | 25 | 100 | INCOMPLETE |
| wishlist | 28 | 99 | INCOMPLETE |
| payment | 28 | 98 | INCOMPLETE |
| category | 27 | 97 | INCOMPLETE |
| search | 29 | 95 | INCOMPLETE |
| media | 15 | 92 | INCOMPLETE |
| pricing | 16 | 94 | INCOMPLETE |
| audit | 5 | 84 | INCOMPLETE |
| review | 34 | 85 | INCOMPLETE |
| shipping | 36 | 76 | INCOMPLETE |

The aggregate unit/report pipeline passed. The hard 100% gate remains FAIL.

## Superseding latest aggregate matrix — 2026-08-24 (post-Catalog/Seller batch)

Measured from the authoritative aggregate HTML/XML report. Every service remains incomplete until its meaningful production line and branch counters reach 100%.

Aggregate: **4,011/4,922 lines**, **5,962/8,644 branches**, **911** missed lines, **2,682** missed branches. Unit tests/report: **PASS**; hard gate: **FAIL**.

| Service/module | Lines missed | Branches missed | Status |
| --- | ---: | ---: | --- |
| checkout | 37 | 209 | INCOMPLETE |
| promotion | 31 | 168 | INCOMPLETE |
| seller | 30 | 141 | INCOMPLETE |
| admin | 12 | 133 | INCOMPLETE |
| identity.http | 0 | 128 | INCOMPLETE |
| catalog | 19 | 121 | INCOMPLETE |
| order | 26 | 114 | INCOMPLETE |
| cart | 48 | 113 | INCOMPLETE |
| cms | 34 | 112 | INCOMPLETE |
| inventory | 84 | 102 | INCOMPLETE |
| notification | 25 | 100 | INCOMPLETE |
| wishlist | 28 | 99 | INCOMPLETE |
| payment | 28 | 98 | INCOMPLETE |
| category | 27 | 97 | INCOMPLETE |
| search | 30 | 97 | INCOMPLETE |
| media | 26 | 94 | INCOMPLETE |
| pricing | 16 | 94 | INCOMPLETE |
| audit | 5 | 88 | INCOMPLETE |
| review | 34 | 85 | INCOMPLETE |
| shipping | 36 | 76 | INCOMPLETE |

The aggregate unit/report pipeline passed; the hard 100% gate remains FAIL.

## Latest aggregate matrix — 2026-08-23

Measured from the latest aggregate CSV. Every service remains incomplete until its meaningful production line and branch counters reach 100%.

| Service/module | Lines missed | Branches missed | Status |
| --- | ---: | ---: | --- |
| checkout | 58 | 209 | INCOMPLETE |
| promotion | 37 | 170 | INCOMPLETE |
| seller | 37 | 141 | INCOMPLETE |
| admin | 21 | 133 | INCOMPLETE |
| identity.http | 0 | 128 | INCOMPLETE |
| catalog | 25 | 121 | INCOMPLETE |
| order | 33 | 114 | INCOMPLETE |
| cart | 56 | 113 | INCOMPLETE |
| cms | 41 | 112 | INCOMPLETE |
| inventory | 92 | 102 | INCOMPLETE |
| notification | 32 | 100 | INCOMPLETE |
| payment | 36 | 98 | INCOMPLETE |

Latest batch: 597 tests / 159 Kotlin test files; unit/report pipeline PASS; hard gate FAIL.

## Fresh aggregate matrix — 2026-08-23

Measured from the regenerated aggregate JaCoCo CSV. Every row remains incomplete until both counters reach 100%.

| Service | Lines missed | Branches missed | Status |
| --- | ---: | ---: | --- |
| checkout | 58 | 209 | INCOMPLETE |
| identity | 150 | 209 | INCOMPLETE |
| promotion | 37 | 170 | INCOMPLETE |
| admin | 21 | 148 | INCOMPLETE |
| seller | 37 | 141 | INCOMPLETE |
| catalog | 25 | 121 | INCOMPLETE |
| notification | 32 | 115 | INCOMPLETE |
| order | 33 | 114 | INCOMPLETE |
| cart | 56 | 113 | INCOMPLETE |
| cms | 42 | 113 | INCOMPLETE |
| inventory | 92 | 102 | INCOMPLETE |
| payment | 36 | 101 | INCOMPLETE |

## Superseding fresh matrix — 2026-08-23

Current package counters from the aggregate CSV. Identity combines its subpackages; shared platform combines common/database/error/kafka/observability/redis/security/service packages.

| Service/module | Lines covered/total | Branches covered/total | Status |
| --- | ---: | ---: | --- |
| identity | 1,255/1,405 | 702/911 | INCOMPLETE |
| category | 161/194 | 198/295 | INCOMPLETE |
| catalog | 142/167 | 386/507 | INCOMPLETE |
| pricing | 68/92 | 204/298 | INCOMPLETE |
| media | 72/104 | 117/211 | INCOMPLETE |
| search | 123/160 | 282/380 | INCOMPLETE |
| cart | 211/267 | 236/349 | INCOMPLETE |
| wishlist | 94/128 | 85/184 | INCOMPLETE |
| promotion | 185/222 | 630/800 | INCOMPLETE |
| inventory | 150/242 | 229/331 | INCOMPLETE |
| order | 186/219 | 273/387 | INCOMPLETE |
| payment | 144/180 | 208/309 | INCOMPLETE |
| shipping | 64/108 | 148/225 | INCOMPLETE |
| refund | 59/100 | 107/178 | INCOMPLETE |
| checkout | 159/217 | 313/522 | INCOMPLETE |
| notification | 108/140 | 197/312 | INCOMPLETE |
| review | 64/104 | 157/242 | INCOMPLETE |
| recommendation | 41/76 | 120/188 | INCOMPLETE |
| analytics | 61/86 | 168/231 | INCOMPLETE |
| admin | 87/108 | 95/243 | INCOMPLETE |
| seller | 73/110 | 211/352 | INCOMPLETE |
| cms | 255/297 | 232/345 | INCOMPLETE |
| audit | 63/78 | 130/218 | INCOMPLETE |
| feature flags | 55/99 | 209/285 | INCOMPLETE |
| API gateway | 45/72 | 4/31 | INCOMPLETE |
| shared platform | 484/506 | 266/304 | INCOMPLETE |

Aggregate: **3,979/4,892 lines (81.3369%)** and **5,907/8,638 branches (68.3839%)**. No service is complete; Phase 10A remains **NO-GO**.

Fresh package counters from `build/reports/jacoco/aggregate/jacoco.xml` on 2026-08-23. A service is complete only when both meaningful line and branch counters reach 100%.

| Service/module | Lines covered/total | Branches covered/total | Status |
| --- | ---: | ---: | --- |
| identity | 1,196/1,341 | 690/911 | INCOMPLETE |
| category | 146/173 | 196/295 | INCOMPLETE |
| catalog | 124/143 | 386/507 | INCOMPLETE |
| pricing | 57/73 | 204/298 | INCOMPLETE |
| media | 59/85 | 117/211 | INCOMPLETE |
| search | 113/143 | 282/380 | INCOMPLETE |
| cart | 195/244 | 235/349 | INCOMPLETE |
| wishlist | 81/109 | 85/184 | INCOMPLETE |
| promotion | 153/184 | 630/800 | INCOMPLETE |
| inventory | 129/213 | 227/331 | INCOMPLETE |
| order | 167/193 | 272/387 | INCOMPLETE |
| payment | 129/157 | 208/309 | INCOMPLETE |
| shipping | 51/87 | 148/225 | INCOMPLETE |
| refund | 46/82 | 103/178 | INCOMPLETE |
| checkout | 126/163 | 311/522 | INCOMPLETE |
| notification | 93/118 | 195/312 | INCOMPLETE |
| review | 49/83 | 157/242 | INCOMPLETE |
| recommendation | 35/64 | 118/188 | INCOMPLETE |
| analytics | 56/75 | 168/231 | INCOMPLETE |
| admin | 60/72 | 95/243 | INCOMPLETE |
| seller | 49/79 | 208/352 | INCOMPLETE |
| cms | 239/274 | 229/345 | INCOMPLETE |
| audit | 60/65 | 130/218 | INCOMPLETE |
| feature flags | 44/82 | 206/285 | INCOMPLETE |
| API gateway | 43/66 | 4/31 | INCOMPLETE |
| shared platform | 472/524 | 266/304 | INCOMPLETE |

Aggregate: **3,972/4,892 lines (81.1938%)** and **5,872/8,638 branches (67.9787%)**. The aggregate test/report task passes, but the unchanged hard JaCoCo verification gate remains below 100%. Phase 10A is **NO-GO**.

## Fresh aggregate counters (2026-08-25)

The current aggregate is **4,064/4,953 lines (82.0109%)** and **6,317/8,636 branches (73.1478%)**. The package heatmap in [`coverage-heatmap.md`](coverage-heatmap.md) is the service-level source for the current ranking. No service is marked complete: every listed package still has missed executable lines or branches, and the hard gate remains **NO-GO**.
