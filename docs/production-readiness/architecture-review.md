# Architecture review record

Status: **PARTIAL — source-reviewed, environment validation pending**  
Owner: Platform engineering  
Last reviewed: 2026-08-19

The detailed review is in [the architecture report](../architecture/production-readiness-review.md). This record is intentionally not a production-ready declaration.

## Verified from repository

- Independent service modules and service-owned migrations exist.
- Critical domains publish through outbox/Kafka boundaries.
- Consumers expose lag/failure counters and have explicit shutdown cleanup in reviewed services.
- Shared cursor pagination caps page size at 100.
- Phase 7 Kubernetes resources include two replicas, probes, resource requests/limits, HPA, and PDB objects.

## Not verified

- Actual ingress/gateway routing, authentication enforcement, and edge rate limiting.
- Database query plans, replica behavior, pool saturation, and failover.
- Kafka partition sizing, lag under peak load, replay, and broker recovery.
- Redis failover, hot-key behavior, and cache stampede control.
- OpenSearch/object-storage degradation and repair.
- Provider reconciliation under duplicate, delayed, or missing callbacks.
- Cluster autoscaling, topology spread, network policies, image signing, and runtime policy.
