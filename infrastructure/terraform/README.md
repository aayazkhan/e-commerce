# Terraform/OpenTofu contract

The repository currently contains Kubernetes manifests but no cloud provider has been selected in the workspace. This directory defines the intended IaC boundary without inventing a cloud account, region, network, or credential.

Before the first real environment is provisioned, select Terraform or OpenTofu and implement provider-specific modules for:

- network/private subnets and firewall/security groups;
- managed PostgreSQL with PITR/WAL and encrypted backups;
- managed Redis with TLS/failover;
- managed Kafka with multi-zone replication/retention;
- OpenSearch and object storage with private access;
- Kubernetes cluster/node pools, autoscaling, workload identity, and ingress/WAF;
- monitoring, certificate management, registry, and external secret store.

Each environment must use a separate state backend and state lock:

```text
environments/dev
environments/staging
environments/production
```

Production state must not be stored in this repository. Run `plan` in CI with reviewed variables, require approval for `apply`, and publish the plan/provider versions as release evidence. No IaC apply was executed for this Phase 9 change because provider/account inputs are absent.
