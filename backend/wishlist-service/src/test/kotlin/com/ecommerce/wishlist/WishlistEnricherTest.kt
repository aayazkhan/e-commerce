package com.ecommerce.wishlist

import com.ecommerce.platform.error.ApiException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WishlistEnricherTest {
    private val record = WishlistRecord("wish-1", "user-1", "product-1", "variant-1", "2026-08-20T10:00:00Z")

    @Test
    fun `enricher combines price and inventory into availability view`() {
        val pricing = server { exchange -> exchange.respond(200, "{\"currency\":\"INR\",\"baseMinor\":2500,\"saleMinor\":1999}") }
        val inventory = server { exchange -> exchange.respond(200, "[{\"available\":2},{\"available\":3}]") }
        try {
            val result = WishlistEnricher(url(pricing), url(inventory)).enrich(record, "INR")
            assertEquals(WishlistItemView(record.id, record.productId, record.variantId, record.createdAt, "IN_STOCK", 5, 1999, "INR"), result)
        } finally {
            pricing.stop(0)
            inventory.stop(0)
        }
    }

    @Test
    fun `enricher handles unavailable price and out of stock inventory`() {
        val pricing = server { exchange -> exchange.respond(404, "not found") }
        val inventory = server { exchange -> exchange.respond(200, "[{\"available\":0}]") }
        try {
            val result = WishlistEnricher(url(pricing), url(inventory)).enrich(record, "INR")
            assertEquals("PRICE_UNAVAILABLE", result.availability)
            assertEquals(0, result.availableQuantity)
            assertEquals(null, result.unitPriceMinor)
        } finally {
            pricing.stop(0)
            inventory.stop(0)
        }
    }

    @Test
    fun `enricher maps malformed and failed downstream responses`() {
        val malformedPrice = server { exchange -> exchange.respond(200, "not-json") }
        val availableInventory = server { exchange -> exchange.respond(200, "[{\"available\":1}]") }
        try {
            val result = WishlistEnricher(url(malformedPrice), url(availableInventory)).enrich(record, "INR")
            assertEquals("PRICE_UNAVAILABLE", result.availability)
            assertEquals(null, result.unitPriceMinor)
        } finally {
            malformedPrice.stop(0)
            availableInventory.stop(0)
        }

        val failedPricing = server { exchange -> exchange.respond(500, "failed") }
        val failedInventory = server { exchange -> exchange.respond(500, "failed") }
        val successfulPricing = pricingForSuccess()
        try {
            assertFailsWith<ApiException> { WishlistEnricher(url(failedPricing), url(availableInventory)).enrich(record, "INR") }
            assertFailsWith<ApiException> { WishlistEnricher(url(successfulPricing), url(failedInventory)).enrich(record, "INR") }
        } finally {
            failedPricing.stop(0)
            failedInventory.stop(0)
            successfulPricing.stop(0)
        }
    }

    @Test
    fun `enricher rejects malformed inventory response`() {
        val pricing = pricingForSuccess()
        val inventory = server { exchange -> exchange.respond(200, "not-json") }
        try {
            assertFailsWith<ApiException> { WishlistEnricher(url(pricing), url(inventory)).enrich(record, "INR") }
        } finally {
            pricing.stop(0)
            inventory.stop(0)
        }
    }

    @Test
    fun `enricher rejects provider responses below the successful status range`() {
        val earlyPricing = server { exchange -> exchange.respond(199, "early") }
        val earlyInventory = server { exchange -> exchange.respond(199, "early") }
        val successfulPricing = pricingForSuccess()
        val successfulInventory = server { exchange -> exchange.respond(200, "[{\"available\":1}]") }
        try {
            assertFailsWith<ApiException> { WishlistEnricher(url(earlyPricing), url(successfulInventory)).enrich(record, "INR") }
            assertFailsWith<ApiException> { WishlistEnricher(url(successfulPricing), url(earlyInventory)).enrich(record, "INR") }
        } finally {
            earlyPricing.stop(0)
            earlyInventory.stop(0)
            successfulPricing.stop(0)
            successfulInventory.stop(0)
        }
    }

    private fun pricingForSuccess() = server { exchange -> exchange.respond(200, "{\"currency\":\"INR\",\"baseMinor\":2500}") }
    private fun url(server: HttpServer) = "http://localhost:${server.address.port}"
    private fun server(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).also {
        it.createContext("/") { exchange -> handler(exchange) }
        it.start()
    }
    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
