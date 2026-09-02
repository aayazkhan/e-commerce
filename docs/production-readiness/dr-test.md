# Disaster-recovery test

Status: **NOT RUN — no multi-zone/region recovery environment was available for this review**

## Recovery sequence

1. Declare incident and freeze non-essential deploys.
2. Confirm last healthy database/Kafka/object-store checkpoints.
3. Promote or restore the designated data plane.
4. Start identity and critical write services first; verify health/readiness.
5. Start inventory, order, checkout, payment, and refund reconciliation.
6. Start downstream consumers and watch lag/DLQ/outbox age.
7. Reconcile payments, inventory reservations, orders, refunds, shipments, and outbox records.
8. Re-enable public traffic progressively and monitor error budget.

Record actual RPO/RTO, unavailable capabilities, manual actions, reconciliation exceptions, and customer-impact window. Do not report target RPO/RTO as achieved without a timed exercise.
