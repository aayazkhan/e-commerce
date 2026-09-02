# Chaos-test results

Status: **NOT RUN — staging fault-injection evidence is pending**

| Fault | Expected behavior | Result |
|---|---|---|
| Kafka unavailable | Orders/payments remain writable through local transaction/outbox; consumers catch up later | NOT RUN |
| Notification provider unavailable | Checkout/order flow is unaffected; bounded retries reach DLQ | NOT RUN |
| Analytics consumer down | Order creation is unaffected; replay is safe and idempotent | NOT RUN |
| PostgreSQL primary failover | Correct readiness transition; no silent partial write; recovery/reconciliation documented | NOT RUN |
| Redis unavailable | Explicit per-service fallback; no unsafe inventory/coupon behavior | NOT RUN |
| OpenSearch unavailable | Search has defined degraded response; catalog writes remain safe | NOT RUN |
| Payment timeout/duplicate webhook | State machine and idempotency prevent duplicate capture/refund | NOT RUN |
| Shipping timeout/duplicate callback | Shipment state remains monotonic and reconcilable | NOT RUN |
| Checkout restart mid-saga | Recovery worker/reconciliation completes or exposes manual action | NOT RUN |
| Pod kill/rolling deploy | No dropped committed work; graceful shutdown drains consumers | NOT RUN |

For every experiment record fault injection, start/end time, blast radius, expected SLO, observed metrics, data reconciliation, and rollback.
