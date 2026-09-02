# API Strategy

The public API is REST over HTTPS with OpenAPI 3.1 contracts. Public paths are versioned under `/api/v1`; additive fields are compatible, while removed or semantically changed fields require a new version or a documented migration window.

## Boundary

```text
Client -> CDN/WAF -> API gateway/BFF -> private service APIs
```

Only the gateway and explicitly approved webhook endpoints are internet-facing. Internal service APIs require workload identity and mTLS. The gateway validates authentication, tenant context, request shape, limits, and correlation metadata; services repeat authorization for defense in depth.

## Resource conventions

| Convention | Rule |
| --- | --- |
| Collection | `GET /api/v1/products` |
| Resource | `GET /api/v1/products/{productId}` |
| Command | `POST /api/v1/orders/{orderId}/cancel` |
| Search | `GET /api/v1/search/products?q=...` |
| Pagination | cursor for large/volatile collections; `nextCursor` is opaque |
| Filtering | explicit allow-listed query parameters |
| Sorting | explicit allow-listed fields and direction |
| Idempotency | `Idempotency-Key` on create/confirm/capture/refund commands |
| Concurrency | `If-Match` with aggregate version where user edits can conflict |
| Caching | `ETag`/`If-None-Match`, explicit `Cache-Control`, and `stale-while-revalidate` only for approved read resources |
| Correlation | `X-Request-Id` plus `traceparent` |
| Content | JSON; UTF-8; dates are UTC ISO-8601 |
| Money | `{ "amountMinor": 1299, "currency": "USD" }` |

## Authentication and authorization

Customer sessions use short-lived access tokens and rotating refresh tokens. Browser sessions prefer secure, HttpOnly, SameSite cookies with CSRF protection. Native clients use platform secure storage. Service calls use workload identities rather than user tokens where possible. Authorization is policy-based: role checks are not sufficient for tenant, seller ownership, resource, or state checks.

## Stable error envelope

```json
{
  "error": {
    "code": "INVENTORY_UNAVAILABLE",
    "message": "One or more items are no longer available.",
    "fieldViolations": [],
    "retryable": false,
    "requestId": "req_01J..."
  }
}
```

Messages are safe for end users. Stack traces, SQL, provider responses, and internal identifiers never cross the public boundary.

## Initial public surface

```text
GET    /api/v1/catalog/products
GET    /api/v1/catalog/products/{productId}
GET    /api/v1/catalog/categories
GET    /api/v1/search/products
GET    /api/v1/cart
POST   /api/v1/cart/items
PATCH  /api/v1/cart/items/{lineId}
DELETE /api/v1/cart/items/{lineId}
POST   /api/v1/cart/merge
GET    /api/v1/wishlist
PUT    /api/v1/wishlist/items/{productId}
DELETE /api/v1/wishlist/items/{productId}
POST   /api/v1/checkout/quote
POST   /api/v1/checkout/confirm
GET    /api/v1/orders
GET    /api/v1/orders/{orderId}
POST   /api/v1/orders/{orderId}/cancel
POST   /api/v1/payments/{paymentId}/refund       # privileged actor only
POST   /api/v1/webhooks/{provider}               # signed provider endpoint
```

The exact schemas are contract artifacts to add in Phase 2. Each operation must define authentication, authorization, request/response examples, error codes, rate limits, and idempotency behavior before implementation.

Client efficiency contracts are part of the same review: catalog/listing resources define ETags and freshness, all large collections define cursor limits, screen-critical aggregation defines a bounded fan-out, and delta-sync resources define a change token. Payment, inventory, final pricing, and checkout confirmation are network-authoritative and are never made valid by a client cache.

## Checkout command contract

```json
{
  "cartId": "cart_123",
  "addressId": "addr_123",
  "shippingMethodId": "standard",
  "paymentMethod": { "provider": "provider_a", "token": "pm_token" },
  "clientTotal": { "amountMinor": 1299, "currency": "USD" }
}
```

`clientTotal` is diagnostic only. The server recomputes product prices, promotions, tax, shipping, inventory, and currency. The response is explicit about the next action:

```json
{
  "orderId": "ord_123",
  "status": "PAYMENT_PENDING",
  "paymentAction": { "type": "REDIRECT", "url": "https://provider.example/..." },
  "serverTotal": { "amountMinor": 1399, "currency": "USD" },
  "requestId": "req_01J..."
}
```

## Compatibility and governance

- OpenAPI changes run breaking-change checks in CI.
- Generated clients are never hand-edited.
- Contract tests run gateway-to-service and service-to-provider adapter boundaries.
- Deprecations include owner, sunset date, migration notes, and telemetry for remaining callers.
- Sensitive fields are marked in schemas and redacted from logs.
