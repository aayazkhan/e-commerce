# Phase 10A checkout unit-test matrix

## Latest authoritative checkpoint — 2026-08-24

The clean aggregate report measures `com/ecommerce/checkout` at **37 missed lines** and **115 missed branches**. The latest targeted contracts covered `PromotionApplyEnvelopeWire` and `OrderRequestWire` nullable/default behavior; no Saga behavior was weakened or replaced. Phase 10A remains **NO-GO**.

## Latest aggregate checkpoint — 2026-08-24 (shared-boundary and identity-route batch)

The fresh aggregate report still measures checkout at **180/217 lines** and **313/522 branches**: **37 missed lines** and **209 missed branches**. This batch added no duplicate checkout tests; the current targeted work was on shared boundary contracts, SearchConsumer lifecycle, and identity route metadata. The Saga matrix below remains the source of truth for implemented checkout success, failure, compensation, recovery, and idempotency behavior.

## Latest aggregate checkpoint — 2026-08-24

The forced aggregate report measures checkout at **180/217 lines** and **313/522 branches**: **37 missed lines** and **209 missed branches**. This batch added no duplicate checkout tests; it focused on media processing, SearchConsumer worker lifecycle, and shared contract tests. The saga matrix below remains the source of truth for implemented checkout success, failure, compensation, recovery, and idempotency behavior.

## Latest Saga checkpoint — 2026-08-23

The latest aggregate checkout package remains **159/217 lines** and **313/522 branches**. The current tests now also cover:

| Scenario | Expected behavior | Test evidence |
| --- | --- | --- |
| Generic downstream failure with reservation release failure | Compensation failure is contained and the checkout is persisted as recoverable | `CheckoutSagaTest.kt` — `unexpected failure contains reservation compensation failure` |
| Generic failure when the persisted snapshot is temporarily absent | No compensation is attempted; the generic recoverable failure is still persisted | `CheckoutSagaTest.kt` — `unexpected failure with missing persisted snapshot records recoverable failure` |
| Non-retryable client order failure | Reservation is released and the checkout remains `FAILED`, not `RECOVERABLE` | `CheckoutSagaTest.kt` — `non retryable client failure remains failed after compensation` |

Measured against the current checkout implementation on 2026-08-23. This matrix records behavior already exercised by unit tests and the remaining production branches; it does not invent compensation behavior that is absent from the saga.

## Existing test coverage

| Area | Tests | Assertions |
| --- | --- | --- |
| Downstream clients | `CheckoutClientsTest.kt` | Empty/invalid carts, quote totals, sparse nullable wire payloads, missing variants/addresses, billing-address fallback, request headers, and non-2xx dependency mapping |
| Saga orchestration | `CheckoutSagaTest.kt` | Completed-idempotency replay, blank coupon handling, promotion commit, payment action-required, invalid quote, retryable/non-retryable/generic failures, missing snapshots, reservation release, order transitions, shipment failure, and contained refund compensation |
| HTTP routes | `CheckoutRoutesTest.kt` | Authentication, required idempotency keys, ownership/not-found reads, retry handling, workflow success, invalid JWT, conflict, dependency failure, unexpected failure, and malformed request mapping |
| Repository | `CheckoutRepositoryBehaviorTest.kt` | Saga start/replay/conflict, ownership reads, checkpoint preservation, recoverable failure, outbox listing, and acknowledgement |

## Implemented compensation matrix

| Failure point | Actual implemented behavior | Test evidence |
| --- | --- | --- |
| Pricing, promotion, or shipping before reservation | Saga exits before an inventory reservation; no compensation is issued | `CheckoutSagaTest.kt` invalid quote and dependency-failure cases |
| Failure after inventory reservation | Releases the reservation through the inventory client | `CheckoutSagaTest.kt` retryable/generic failure cases |
| Shipment failure after redemption/payment/order | Releases reservation, releases promotion, transitions the order to `REFUND_PENDING`, and requests a payment refund | `CheckoutSagaTest.kt` shipment-failure case |
| Generic exception | Releases the reservation and persists a recoverable failure; the generic catch path does not request a payment refund | `CheckoutSagaTest.kt` generic-failure case and pre-reservation failure |
| Compensation provider failure | Swallows release/transition/refund adapter failures and still persists the original checkout failure | `CheckoutSagaTest.kt` contained-compensation cases |
| Payment action required | Persists `PAYMENT_ACTION_REQUIRED` and stops before confirmation, inventory commit, and shipment | `CheckoutSagaTest.kt` action-required case |

## Latest checkpoint

The latest aggregate checkout package measurement is `159/217` lines and `313/522` branches: 58 missed lines and 209 missed branches. The largest class-level markers remain application wiring and private/generated wire serializers. The saga tests continue to cover the reachable compensation and recovery behavior described below.

## Remaining branch work

The largest remaining source backlog is compact route wiring and private wire DTO defaults, including `AddressWire` (19 missed branches) and `PriceRequest` (20 missed branches). The aggregate Phase 10A gate remains **NO-GO** until all meaningful line and branch paths are covered.

## Fresh checkout checkpoint (2026-08-25)

The current JaCoCo package counter is **126/163 lines** and **407/522 branches** (37 missed lines, 115 missed branches). Reachable saga tests cover completed idempotency, action-required payment, order/shipment failures, compensation, recovery, and nullable persisted snapshots. The remaining `runCheckoutSaga` miss is tracked as an exact persisted-recovery branch in `remaining-branch-inventory.md`; no completion is claimed.
