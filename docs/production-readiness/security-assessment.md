# Security assessment

Status: **PARTIAL — source review only; no external DAST/penetration test completed**  
Last reviewed: 2026-08-19

## Reviewed controls

- JWT/HMAC verification and permission checks exist in service routes reviewed during Phase 7.
- Admin/seller/CMS routes use explicit permissions in the reviewed implementations.
- Shared money type and cursor pagination impose domain limits in shared code.
- CORS and default headers are installed in many services.
- Provider API keys are read from configuration rather than hard-coded in the checked source.

## Required tests before approval

- IDOR: user A cannot read or mutate user B’s cart, orders, reviews, media, addresses, or notifications.
- Mass assignment: unknown/protected JSON fields cannot change owner, status, price, role, permissions, or audit actor.
- Input abuse: oversized JSON, deep JSON, invalid UTF-8, path traversal, SSRF, header injection, and unsafe URL schemes.
- Money/invariants: negative/overflow amounts, currency mismatch, rounding, coupon over-redemption, inventory underflow, and payment/order mismatch.
- Consistency: duplicate idempotency keys, duplicate webhooks, out-of-order events, replayed Kafka records, and saga compensation.
- Search/object storage: document-level authorization, tenant isolation, signed URL expiry, content-type/size restrictions, and malware scanning policy.
- Secrets: secret scanning, dependency audit, image scan, key rotation rehearsal, and log review for tokens/PII.

No “secure” or “penetration-tested” claim should be made until the results are attached with tool versions, scope, exclusions, and remediations.
