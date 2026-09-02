# Security Architecture

## Trust boundaries

```text
Untrusted browser/mobile input
  -> CDN/WAF
  -> gateway validation and rate limits
  -> authenticated service boundary
  -> service-owned data and providers
```

The browser, mobile apps, search index, cache, event bus, and third-party providers are not trusted equally. The source of truth is revalidated at the service that owns the fact.

## Identity and authorization

- OIDC-compatible identity provider for customer and workforce authentication.
- Short-lived access tokens; rotating, revocable refresh tokens.
- Secure, HttpOnly, SameSite cookies for browser sessions; CSRF token for state-changing cookie requests.
- Native tokens in Keychain/Keystore-backed secure storage.
- MFA and step-up authentication for admin, seller payout, refund, and security-sensitive changes.
- RBAC for coarse role assignment plus tenant/resource/state policies for actual authorization.
- Service-to-service mTLS and workload identity; no static credentials in application code.
- Tenant context is derived from the authenticated principal and checked against resource ownership.

## Data protection

- TLS at every network hop; modern cipher policy at the edge.
- Encryption at rest for databases, backups, object storage, logs, and queues.
- Payment card data is delegated to tokenizing providers; the platform stores provider tokens and minimal evidence, never PAN/CVV.
- Passwords use a memory-hard password hash supplied by the identity provider or approved password library.
- PII is classified, minimized, masked in logs, and deleted/exported through privacy workflows.
- Secrets are injected at runtime from a secret manager and rotated without image rebuilds.

## Application controls

- schema validation and allow-listed filters/sorts at every API boundary;
- parameterized SQL and ORM query bindings;
- output encoding and a strict Content Security Policy for web surfaces;
- origin-checked CORS and CSRF protection;
- SSRF protection for media/import URLs and provider callbacks;
- signed webhook verification, replay protection, and provider event deduplication;
- idempotency keys on retryable financial and inventory commands;
- rate limits by identity, tenant, IP, route, and risk score;
- brute-force and credential-stuffing protection;
- immutable audit log for auth, permissions, price, inventory, payment, refund, and seller actions.

## Threat model priorities

| Threat | Primary mitigation | Detection |
| --- | --- | --- |
| account takeover | MFA, rotation, risk limits, breached-password controls | auth anomaly metrics and alerts |
| price/promotion tampering | server-side recalculation and signed/owned rules | mismatch and abuse events |
| inventory oversell | reservation transaction, unique constraints, reconciliation | reservation failure/negative-stock alerts |
| duplicate payment/order | idempotency records and provider keys | duplicate-key dashboards |
| tenant data leakage | policy checks, tenant-scoped indexes/RLS, contract tests | audit and access anomaly alerts |
| webhook forgery/replay | signature, timestamp window, unique provider event ID | verification failure alerts |
| API abuse | WAF, route limits, quotas, adaptive controls | rate-limit telemetry |
| supply-chain compromise | lockfiles, SCA, image signing, provenance, minimal images | CI security gates |

## Security lifecycle

- threat model each new boundary and payment/provider integration;
- secret and dependency scanning on every change;
- SAST, SCA, container, IaC, and DAST gates appropriate to the environment;
- independent penetration test before production launch and after material auth/payment changes;
- rotate credentials and keys on a documented schedule;
- maintain incident response, breach notification, and rollback runbooks.

## Privacy

Consent gates analytics and personalization. Data subject export, deletion, correction, and processing restriction are asynchronous jobs with identity verification and audit. Regional storage and tax requirements are configuration and deployment concerns, not client-side assumptions.

