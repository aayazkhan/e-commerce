# Event Architecture

## Envelope

```json
{
  "eventId": "evt_01J...",
  "eventType": "OrderCreated",
  "schemaVersion": 1,
  "occurredAt": "2026-08-18T10:00:00Z",
  "producer": "order-service",
  "tenantId": "tenant_123",
  "aggregateType": "Order",
  "aggregateId": "ord_123",
  "correlationId": "req_01J...",
  "causationId": "cmd_01J...",
  "payload": {}
}
```

## Initial event catalog

| Event | Producer | Consumers | Delivery expectation |
| --- | --- | --- | --- |
| UserRegistered | auth | analytics, recommendation, notification | at least once |
| ProductUpdated | catalog | search, recommendation, analytics | ordered per product |
| PriceChanged | catalog/promotion | search, cart, analytics | ordered per SKU |
| InventoryReserved | inventory | order, analytics | idempotent |
| InventoryReleased | inventory | order, analytics | idempotent |
| OrderCreated | order | payment, notification, analytics | idempotent |
| OrderStateChanged | order | notification, analytics, seller projections | ordered per order |
| PaymentCompleted | payment | order, inventory, notification, analytics | signed-source evidence |
| PaymentFailed | payment | order, inventory, notification, analytics | idempotent |
| RefundCreated | payment | order, analytics, notification | idempotent |
| OrderShipped | shipping | order, notification, analytics | idempotent |
| OrderDelivered | shipping | order, notification, analytics | idempotent |
| CartAbandoned | cart | analytics, notification, recommendation | scheduled/at least once |
| ReviewPublished | review | catalog, recommendation, analytics | idempotent |
| ContentPublished | cms | gateway/cache, analytics | invalidates relevant cache |
| CategoryUpdated | category-service | search, gateway/cache | ordered per category |
| ProductCreated / ProductUpdated / ProductPublished / ProductUnpublished / ProductDeleted | catalog-service | search, analytics | ordered per product |
| PriceUpdated / PriceDeleted | pricing-service | search, cart, analytics | ordered per price aggregate |
| MediaReady / MediaDeleted | media-service | catalog, CDN/cache | at least once |
| SellerCreated / SellerUpdated / SellerVerified / SellerRejected / SellerSuspended | seller-service | audit, analytics, admin projections | idempotent |
| CmsCreated / CmsUpdated / CmsPublished / CmsUnpublished / CmsRolledBack | cms-service | gateway/cache, audit, analytics | idempotent |
| FeatureFlagChanged | feature-flag-service | audit, config caches | idempotent |
| AdminBulkJobCreated | admin-service | audit, operations | durable outbox |

## Topic policy

Topics are named by bounded context and major version. Partition keys preserve aggregate ordering. Events are immutable; corrections are new events. Consumers must tolerate duplicates, unknown additive fields, delayed delivery, and replay. Schema compatibility is checked in CI and event payloads are classified for PII retention.
