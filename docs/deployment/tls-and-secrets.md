# TLS and secret operations

## TLS path

```text
Internet → DNS/CDN/WAF → TLS load balancer/Ingress → gateway → private services
```

The production ingress example uses cert-manager annotations and a named TLS Secret. The actual issuer, DNS records, certificate account, and renewal controller are cluster-owned inputs and are not configured in this repository.

Required checks:

- public API, admin, seller, CDN, and media domains resolve to the intended edge;
- TLS redirects/modern protocol policy are tested;
- internal service/database/Redis/Kafka encryption is enabled where the managed service supports it;
- certificate expiry probe alerts at 30, 14, and 3 days;
- renewal is rehearsed without restarting application binaries.

## External secret flow

Use External Secrets Operator or the selected cloud secret manager with workload identity. The application receives only the runtime Secret projection; source secret values and provider credentials remain outside Git and images. See `deploy/k8s/phase9/external-secrets.example.yaml`.

Rotation must preserve overlap where required: JWT current + previous verification key, database dual credential or coordinated rollout, and provider credentials with an explicit validity window. Record secret version and verification in the change ticket.

No TLS issuance, renewal, external-secret sync, or rotation drill was executed in this workspace.
