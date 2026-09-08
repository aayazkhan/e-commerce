package com.ecommerce.gateway

/**
 * One routable path prefix and the backend base URL it forwards to.
 *
 * Resource ownership is not one-segment-per-service: several services expose an
 * admin sub-namespace under the same "/api/v1/admin/<resource>" shape as their
 * public resource (e.g. "/api/v1/orders" and "/api/v1/admin/orders" both belong
 * to order-service), while admin-service itself only owns a handful of
 * cross-cutting paths ("/api/v1/admin/dashboard", ".../jobs", ".../products").
 * Matching by longest-prefix-wins over this flat list resolves that correctly
 * without hardcoding per-path exceptions.
 */
data class ServiceRoute(val prefix: String, val baseUrl: String)

internal fun serviceBaseUrl(
    serviceName: String,
    port: Int,
    envLookup: (String) -> String? = System::getenv,
): String {
    val envVar = serviceName.uppercase().replace('-', '_') + "_URL"
    return envLookup(envVar) ?: "http://$serviceName:$port"
}

/**
 * The "/api/v1/internal" paths (order/payment/shipping) are deliberately absent:
 * those use internalToken auth for direct service-to-service calls and must
 * never be reachable through the public gateway.
 */
val serviceRoutes: List<ServiceRoute> = listOf(
    ServiceRoute("/api/v1/admin/audit", serviceBaseUrl("audit-service", 8102)),
    ServiceRoute("/api/v1/admin/analytics", serviceBaseUrl("analytics-service", 8099)),
    ServiceRoute("/api/v1/admin/categories", serviceBaseUrl("category-service", 8082)),
    ServiceRoute("/api/v1/admin/inventory", serviceBaseUrl("inventory-service", 8087)),
    ServiceRoute("/api/v1/admin/orders", serviceBaseUrl("order-service", 8091)),
    ServiceRoute("/api/v1/admin/promotions", serviceBaseUrl("promotion-service", 8090)),
    ServiceRoute("/api/v1/admin/coupons", serviceBaseUrl("promotion-service", 8090)),
    ServiceRoute("/api/v1/admin/pricing", serviceBaseUrl("pricing-service", 8084)),
    ServiceRoute("/api/v1/admin/refunds", serviceBaseUrl("refund-service", 8094)),
    ServiceRoute("/api/v1/admin/reviews", serviceBaseUrl("review-service", 8097)),
    ServiceRoute("/api/v1/admin/search", serviceBaseUrl("search-service", 8086)),
    ServiceRoute("/api/v1/admin/cms", serviceBaseUrl("cms-service", 8101)),
    ServiceRoute("/api/v1/admin/feature-flags", serviceBaseUrl("feature-flag-service", 8103)),
    ServiceRoute("/api/v1/admin", serviceBaseUrl("admin-service", 8104)),
    ServiceRoute("/api/v1/auth", serviceBaseUrl("identity-service", 8081)),
    ServiceRoute("/api/v1/users", serviceBaseUrl("identity-service", 8081)),
    ServiceRoute("/api/v1/profile", serviceBaseUrl("identity-service", 8081)),
    ServiceRoute("/api/v1/addresses", serviceBaseUrl("identity-service", 8081)),
    ServiceRoute("/api/v1/categories", serviceBaseUrl("category-service", 8082)),
    ServiceRoute("/api/v1/products", serviceBaseUrl("catalog-service", 8083)),
    ServiceRoute("/api/v1/pricing", serviceBaseUrl("pricing-service", 8084)),
    ServiceRoute("/api/v1/media", serviceBaseUrl("media-service", 8085)),
    ServiceRoute("/api/v1/search", serviceBaseUrl("search-service", 8086)),
    ServiceRoute("/api/v1/inventory", serviceBaseUrl("inventory-service", 8087)),
    ServiceRoute("/api/v1/cart", serviceBaseUrl("cart-service", 8088)),
    ServiceRoute("/api/v1/wishlist", serviceBaseUrl("wishlist-service", 8089)),
    ServiceRoute("/api/v1/promotions", serviceBaseUrl("promotion-service", 8090)),
    ServiceRoute("/api/v1/orders", serviceBaseUrl("order-service", 8091)),
    ServiceRoute("/api/v1/payments", serviceBaseUrl("payment-service", 8092)),
    ServiceRoute("/api/v1/shipping", serviceBaseUrl("shipping-service", 8093)),
    ServiceRoute("/api/v1/refunds", serviceBaseUrl("refund-service", 8094)),
    ServiceRoute("/api/v1/checkout", serviceBaseUrl("checkout-service", 8095)),
    ServiceRoute("/api/v1/notifications", serviceBaseUrl("notification-service", 8096)),
    ServiceRoute("/api/v1/providers", serviceBaseUrl("notification-service", 8096)),
    ServiceRoute("/api/v1/reviews", serviceBaseUrl("review-service", 8097)),
    ServiceRoute("/api/v1/recommendations", serviceBaseUrl("recommendation-service", 8098)),
    ServiceRoute("/api/v1/analytics", serviceBaseUrl("analytics-service", 8099)),
    ServiceRoute("/api/v1/seller", serviceBaseUrl("seller-service", 8100)),
    ServiceRoute("/api/v1/cms", serviceBaseUrl("cms-service", 8101)),
    ServiceRoute("/api/v1/feature-flags", serviceBaseUrl("feature-flag-service", 8103)),
).sortedByDescending { it.prefix.length }

private fun matches(path: String, prefix: String): Boolean =
    path == prefix || path.startsWith("$prefix/")

fun resolveServiceRoute(path: String): ServiceRoute? =
    serviceRoutes.firstOrNull { matches(path, it.prefix) }
