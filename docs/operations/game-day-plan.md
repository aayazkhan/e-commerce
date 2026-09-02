# Phase 8 game-day plan

Run in staging with synthetic users and payment/shipping providers. Obtain an incident commander, service owners, observer, and data reconciler before starting.

| Exercise | Inject | Success criteria |
|---|---|---|
| Async isolation | Stop analytics and notification consumers | Orders/payments continue; lag grows and later recovers; no duplicate delivery |
| Broker recovery | Block Kafka for producers/consumers, then restore | Outbox is durable; bounded backlog drains; replay is idempotent |
| Provider recovery | Delay payment/shipping/notification provider responses | No request-thread retry storm; explicit pending/DLQ/reconciliation behavior |
| Data recovery | Restore a database snapshot into isolation | RPO/RTO measured; invariants and event replay pass |
| Rolling deploy | Kill pods during traffic and consumer work | No committed work lost; readiness and shutdown behavior are observable |

Capture a timeline, dashboards, commands, operator actions, customer impact, reconciliation counts, and follow-up owners. A successful tabletop is not a successful production recovery test.
