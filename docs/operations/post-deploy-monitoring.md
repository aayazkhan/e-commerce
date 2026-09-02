# Post-deploy monitoring plan

Compare every deployment with the pre-release baseline and the previous stable release:

| Checkpoint | Required checks |
|---|---|
| T+0 | rollout/readiness, pod restarts, 5xx, latency, error logs, migration status |
| T+15 min | traffic, checkout/payment errors, DB pool/locks, Kafka lag/DLQ, Redis, search |
| T+30 min | canary thresholds, business order/payment/inventory counters, provider latency |
| T+1 hour | HPA behavior, resource throttling, outbox age, notification delivery, reconciliation |
| T+4 hours | hourly aggregates, cache/search freshness, certificate/backup alerts, support signals |
| T+12 hours | overnight traffic, consumer recovery, slow queries, error-budget burn |
| T+24 hours | daily revenue/order/payment/inventory reconciliation and customer-impact review |
| T+72 hours | release retrospective, backlog/P0–P3 classification, rollback window closure |

Abort or pause expansion when the release exceeds the approved baseline-relative 5xx/P99/checkout/payment thresholds. Preserve durable state and use the rollback runbook; do not reverse financial records by rolling back an application image.
