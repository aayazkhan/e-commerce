# Phase 10A service test status

Baseline measured before Phase 10A additions. `Partial` means the existing suite covers only a small domain/configuration subset; `None` means no unit test source existed for the service.

| Service | Baseline line | Baseline branch | Existing unit status | Priority |
| --- | ---: | ---: | --- | --- |
| identity-service | 7.36% | 2.08% | Partial | P0 |
| category-service | 1.83% | 1.36% | Partial | P1 |
| catalog-service | 1.59% | 1.40% | Partial | P1 |
| pricing-service | 1.61% | 0.00% | Partial | P0 |
| media-service | 12.50% | 4.57% | Partial | P1 |
| search-service | 2.47% | 0.00% | Partial | P1 |
| cart-service | 0.80% | 2.58% | Partial | P0 |
| wishlist-service | 0.00% | 0.00% | None | P1 |
| promotion-service | 2.82% | 1.00% | Partial | P0 |
| inventory-service | 0.43% | 1.18% | Partial | P0 |
| order-service | 12.37% | 0.78% | Partial | P0 |
| payment-service | 7.28% | 0.97% | Partial | P0 |
| shipping-service | 5.56% | 1.33% | Partial | P1 |
| refund-service | 0.00% | 0.00% | None | P0 |
| checkout-service | 1.98% | 0.00% | Partial | P0 |
| notification-service | 1.83% | 0.65% | Partial | P1 |
| review-service | 0.00% | 0.00% | None | P1 |
| recommendation-service | 11.11% | 0.00% | Partial | P2 |
| analytics-service | 0.00% | 0.00% | None | P1 |
| admin-service | 0.00% | 0.00% | None | P1 |
| seller-service | 3.23% | 0.57% | Partial | P0 |
| cms-service | 2.56% | 0.61% | Partial | P1 |
| audit-service | 0.00% | 0.00% | None | P1 |
| feature-flag-service | 5.71% | 0.00% | Partial | P1 |
| api-gateway | 65.15% | 12.90% | Partial | P1 |
| shared modules | 42.05% | 24.82% | Partial | P0 |

## Completion rule

After each service, record before/after line and branch coverage, tests added, and remaining uncovered lines. Do not move to the next service with a low service-level result, except when a shared dependency is explicitly being completed first.
