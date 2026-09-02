# Operational ownership matrix

Names and paging destinations are deployment inputs; the rows below define required ownership, not assigned individuals.

| Capability | Primary owner | Secondary | Page on |
|---|---|---|---|
| Gateway/Ingress/WAF/TLS | Platform/SRE | Security | 5xx, certificate, routing, edge saturation |
| PostgreSQL/migrations/backups | Database platform | Service owner | pool exhaustion, lock/deadlock, backup/restore failure |
| Kafka/outbox/DLQ | Messaging platform | Consumer owner | broker health, lag, under-replication, DLQ growth |
| Redis | Platform/SRE | Service owner | failover, evictions, latency, hot keys |
| Search/OpenSearch | Search team | Platform | cluster health, indexing lag, query failure |
| Checkout/order/inventory | Commerce team | Platform | checkout failure, reservations, stuck saga, reconciliation mismatch |
| Payment/refund | Payments team | Finance/commerce | provider timeout/failure, duplicate webhook, mismatch |
| Shipping | Fulfillment team | Commerce | carrier timeout, callback mismatch, tracking outage |
| Notification | Communications team | Platform | provider outage, retry storm, DLQ |
| Identity/security | Identity team | Security | auth failure, suspicious login, JWT/credential issue |
| Analytics/recommendation/review | Growth/data team | Commerce | consumer lag, replay failure, privacy issue |

Before go-live replace team labels with pager aliases, escalation numbers, backup coverage, and acknowledgement/resolve targets.
