package com.ecommerce.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceRoutesTest {
    @Test
    fun `uses an env override when present instead of the default host`() {
        val url = serviceBaseUrl("cart-service", 8088) { name ->
            if (name == "CART_SERVICE_URL") "http://localhost:9999" else null
        }

        assertEquals("http://localhost:9999", url)
    }

    @Test
    fun `falls back to the default in-cluster host when no override is set`() {
        val url = serviceBaseUrl("cart-service", 8088) { null }

        assertEquals("http://cart-service:8088", url)
    }

    @Test
    fun `does not treat a path sharing a prefix word as a match`() {
        assertNull(resolveServiceRoute("/api/v1/carts-report"))
    }

    @Test
    fun `matches the exact prefix with no trailing segment`() {
        val route = resolveServiceRoute("/api/v1/cart")

        assertEquals("cart-service", route?.baseUrl?.let { java.net.URI(it).host })
    }
}
