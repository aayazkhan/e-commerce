# Phase 5 event contracts

Every event is wrapped by the shared `EventEnvelope` with `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `producer`, `tenantId`, `aggregateType`, `aggregateId`, `correlationId`, and a JSON payload. Producers write the envelope payload to their service-owned outbox in the same PostgreSQL transaction as the business mutation. Consumers use the service-owned inbox tables before applying a message.

| Producer | Event types | Aggregate payload |
| --- | --- | --- |
| Order | `OrderCreated`, `OrderInventoryReserved`, `OrderPaid`, `OrderConfirmed`, `OrderStatusChanged`, `OrderCancelled`, `OrderCancellationRequested`, `OrderReturnRequested`, `OrderRefunded` | Complete immutable `OrderResponse` snapshot or return response |
| Payment | `PaymentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `PaymentRefunded`, `PaymentStatusChanged` | `PaymentResponse` with no card data |
| Shipping | `ShipmentDelivered`, `ShipmentStatusChanged` | `ShipmentResponse` |
| Refund | `RefundRequested`, `RefundCompleted` | `RefundResponse` |
| Checkout | Saga state transitions | `CheckoutResponse` |

Handlers must be idempotent by `eventId`, tolerate duplicate provider callbacks, and publish only after the database transaction commits. Event schemas are additive-versioned; consumers must ignore unknown fields and route incompatible versions to a dead-letter workflow.
