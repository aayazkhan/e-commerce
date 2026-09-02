# Incident management

## Severity and response

| Severity | Example | Initial response | Owner/escalation |
|---|---|---:|---|
| SEV-1 | Payment/inventory corruption, broad checkout outage, active data exposure | 5 minutes | Incident commander → platform + commerce + security executives |
| SEV-2 | Material checkout/payment degradation, Kafka/DB outage with recovery path | 15 minutes | Service owner → platform on-call |
| SEV-3 | Single service degradation, notification/search backlog, elevated errors | 1 business hour | Owning service team |
| SEV-4 | Non-customer-impacting defect or alert tuning | Next business day | Backlog owner |

## Incident sequence

1. Acknowledge the alert and assign incident commander, technical lead, communications lead, and scribe.
2. Record timeline, release/image, environment, scope, customer impact, and dashboard links.
3. Stabilize first: stop a rollout, shed non-critical traffic, disable a safe feature flag, or fail over a provider without mutating durable financial state.
4. Preserve evidence and avoid logging/forwarding tokens, payment credentials, or raw customer PII.
5. Reconcile orders, payments, inventory, refunds, outbox, and consumer projections before declaring recovery.
6. Communicate at the agreed cadence; close only after monitoring and customer-impact checks are stable.
7. Publish a blameless postmortem with trigger, detection gap, timeline, root cause, impact, corrective actions, and owners.

Break-glass access requires explicit authorization, short-lived credentials, audit, reason, and automatic expiration. Security incidents additionally require credential revocation and the security response process.
