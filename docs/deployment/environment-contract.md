# Environment contract

Status: **Phase 9 contract — provider values are deployment inputs, not repository defaults**  
Last reviewed: 2026-08-20

The application configuration is HOCON plus environment substitution. `application.conf` files are service-owned and must not be copied between environments. The environment name is explicit (`local`, `development`, `test`, `staging`, or `production`); production values are supplied by the deployment platform.

## Environment policy

| Environment | Purpose | Data | Secret source | Deployment rule |
|---|---|---|---|---|
| `local` | Developer feedback | Disposable synthetic data | Local `.env` outside version control | No production endpoints |
| `development` | Shared integration | Synthetic/non-sensitive | Dev Kubernetes Secret or external store | Automatic after CI gates |
| `test` | Unit/integration/contract tests | Fixtures/Testcontainers | Test-only generated values | Ephemeral and isolated |
| `staging` | Production-like rehearsal | Masked/synthetic only | Staging external secret store | Smoke/E2E/load/chaos gates |
| `production` | Customer traffic | Managed customer data | External secret store/identity | Approval, immutable image, rollback plan |

## Value classes

- **Non-secret:** safe to place in a ConfigMap or deployment metadata after reviewing whether it contains customer information.
- **Secret:** database credentials, JWT material, internal tokens, provider credentials, OAuth credentials, signing keys, and connection strings containing credentials. Store in an external secret manager and project into Kubernetes Secrets; never commit the value.
- **Sensitive operational:** URLs, tenant IDs, bucket names, and feature defaults are not credentials but still require environment ownership and change review.

## Common variables

| Variable | Services | Required | Default | Class | Description/example |
|---|---|---:|---|---|---|
| `APP_ENV` | Gateway/edge | No | none in production | Non-secret | `local`, `development`, `test`, `staging`, `production` |
| `PORT` | Gateway | No | service config port | Non-secret | Edge listen port; use `8080` in the container |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka producers/consumers | Yes where Kafka is enabled | none | Sensitive operational | Managed TLS broker endpoints; no credentials in the URL |
| `JWT_KEYS` | JWT-verifying services | Yes | none | Secret | Key-ring format consumed by the service; current and previous verification keys during grace period |
| `JWT_ACTIVE_KEY_ID` | Identity | Yes | none | Non-secret | Active signing key ID; private key material remains in `JWT_KEYS`/secret store |
| `INTERNAL_SERVICE_TOKEN` | Admin/seller and internal boundaries | Yes where configured | none | Secret | Service-to-service authentication token |
| `DATABASE_USERNAME` | Phase 6/7 services using shared username | Yes | none | Secret | Least-privilege application DB user |
| `DATABASE_PASSWORD` | Phase 6/7 services using shared username | Yes | none | Secret | Application DB password |
| `REDIS_URL` | Phase 6/7 Redis users | Yes where configured | none | Secret if credentialed | TLS Redis endpoint; do not put passwords in ConfigMaps |
| `IDENTITY_TENANT_ID` | Identity and event producers | Yes | none | Non-secret | Tenant/account namespace |

## Per-service database variables

For every service in the list below, the URL, username, and password variables are required unless the service is disabled in the selected environment. A service must use its own database owner and must not use a PostgreSQL superuser.

| Variable pattern | Service values |
|---|---|
| `<SERVICE>_DATABASE_URL` | `IDENTITY`, `CATEGORY`, `CATALOG`, `PRICING`, `MEDIA`, `SEARCH`, `INVENTORY`, `CART`, `WISHLIST`, `PROMOTION`, `CHECKOUT`, `ORDER`, `PAYMENT`, `SHIPPING`, `REFUND`, `NOTIFICATION`, `REVIEW`, `RECOMMENDATION`, `ANALYTICS`, `ADMIN`, `SELLER`, `CMS`, `AUDIT`, `FLAGS` |
| `<SERVICE>_DATABASE_USERNAME` | Same service list; use a least-privilege application role |
| `<SERVICE>_DATABASE_PASSWORD` | Same service list; external secret only |

The HOCON aliases `DATABASE_USERNAME`/`DATABASE_PASSWORD` are used by the Phase 6/7 services whose config explicitly references them. Do not set both aliases and per-service values to conflicting identities.

## Service-specific variables

| Variable(s) | Service | Required | Class | Description |
|---|---|---:|---|---|
| `IDENTITY_REDIS_URL`, `IDENTITY_TENANT_ID`, `CHALLENGE_DELIVERY_URL`, `CHALLENGE_DELIVERY_TOKEN`, `GOOGLE_USERINFO_URL`, `APPLE_USERINFO_URL` | Identity | Yes for enabled flow | Mixed | Session/rate limit, tenant, internal delivery, OAuth userinfo; delivery token is secret |
| `CATEGORY_REDIS_URL` | Category | Yes | Sensitive operational | Category cache endpoint |
| `CATALOG_REDIS_URL`, `CATALOG_BASE_URL`, `CATALOG_SERVICE_URL`, `CATALOG_URL`, `CATALOG_INTERNAL_TOKEN` | Catalog/consumers | Yes where referenced | Mixed | Catalog cache, internal/public service endpoints, internal token secret |
| `PRICING_REDIS_URL`, `PRICING_BASE_URL`, `PRICING_SERVICE_URL` | Pricing/consumers | Yes where referenced | Mixed | Pricing cache and service endpoint |
| `MEDIA_S3_BUCKET`, `MEDIA_S3_ENDPOINT`, `MEDIA_PUBLIC_BASE_URL` | Media | Yes | Non-secret | Private object bucket, endpoint, CDN/public base URL; SDK credentials come from secret/workload identity |
| `OPENSEARCH_URL`, `OPENSEARCH_USERNAME`, `OPENSEARCH_PASSWORD` | Search | Yes | Mixed | Search endpoint and credentials; username/password are secret-managed as a pair |
| `SEARCH_CATALOG_BASE_URL` / `CATALOG_BASE_URL`, `SEARCH_PORT` | Search | Yes where referenced | Non-secret | Catalog reindex endpoint and service port |
| `INVENTORY_REDIS_URL`, `INVENTORY_BASE_URL`, `INVENTORY_SERVICE_URL`, `INVENTORY_URL`, `DEFAULT_WAREHOUSE_ID` | Inventory/consumers | Yes | Mixed | Reservation cache, endpoint, warehouse; no stock decisions from cache alone |
| `CART_REDIS_URL`, `CART_SERVICE_URL`, `CART_PORT` | Cart | Yes | Mixed | Cart cache/endpoint/listen port |
| `WISHLIST_PRICING_BASE_URL`, `WISHLIST_INVENTORY_BASE_URL` | Wishlist | Yes | Non-secret | Downstream service endpoints |
| `PROMOTION_REDIS_URL`, `PROMOTION_BASE_URL`, `PROMOTION_SERVICE_URL`, `PROMOTION_URL` | Promotion/consumers | Yes | Mixed | Promotion cache and endpoint |
| `CHECKOUT_*_BASE_URL`, `CHECKOUT_WAREHOUSE_ID` | Checkout | Yes | Non-secret | Cart, catalog, identity, inventory, pricing, promotion, order, payment, shipping endpoints and warehouse |
| `ORDER_SERVICE_URL`, `ORDER_URL`, `ORDER_INTERNAL_TOKEN` | Order/consumers | Yes | Mixed | Order endpoint and internal token |
| `PAYMENT_PROVIDER_BASE_URL`, `PAYMENT_PROVIDER_API_KEY`, `PAYMENT_PROVIDER_WEBHOOK_SECRET`, `PAYMENT_RECONCILIATION_INTERVAL_SECONDS` | Payment | Yes for provider mode | Mixed | Provider API, secret, HMAC secret, reconciliation interval |
| `SHIPPING_PROVIDER_BASE_URL`, `SHIPPING_PROVIDER_API_KEY`, `SHIPPING_PROVIDER_WEBHOOK_SECRET` | Shipping | Yes for provider mode | Mixed | Carrier API, API key, HMAC secret |
| `REFUND_PAYMENT_BASE_URL` | Refund | Yes | Non-secret | Payment service internal endpoint |
| `NOTIFICATION_DEFAULT_CHANNELS`, `NOTIFICATION_*_ENDPOINT`, `NOTIFICATION_*_API_KEY` | Notification | Yes for enabled channels | Mixed | Channels plus email/SMS/FCM/APNs provider endpoints and credentials |
| `REVIEW_MODERATION_AUTO_PUBLISH` | Review | Yes | Non-secret | Explicit moderation policy; production default must be approved |
| `RECOMMENDATION_REDIS_URL` | Recommendation | Yes | Sensitive operational | Cache endpoint; popular-product fallback remains source-safe |
| `ADMIN_*_URL`, `SELLER_*_URL`, `CMS_URL`, `AUDIT_URL`, `FLAGS_URL`, `ANALYTICS_URL` | Operations services | Yes where configured | Non-secret | Explicit downstream service endpoints |

The source-level `application.conf` files are authoritative for exact property names. The contract above covers the environment substitutions and service-specific secrets; CI should reject a deployment whose rendered configuration has unresolved required properties.

## Secret rotation contract

Rotation is a deployment/configuration operation, not a binary rebuild:

1. Add the new credential/key alongside the old value in the external store.
2. For JWT, publish the new signing key ID while retaining the old public verification key through token expiry plus clock-skew grace.
3. Roll or reload consumers with the new secret and verify health, authentication, provider probes, and error rates.
4. Revoke the old credential only after all replicas and dependent jobs report the new version.
5. Record secret version, operator, timestamp, validation, and rollback/restore procedure in the change record.

Required rotation drills: database, Redis, Kafka, JWT, OAuth, payment, shipping, FCM/APNs, SMTP, and object-storage credentials. No drill was executed in this repository review.
