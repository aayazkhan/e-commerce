package com.ecommerce.cart

import com.ecommerce.platform.error.ApiException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CartClientsTest {
    @Test
    fun `pricing client prefers sale price and inventory client sums stock`() {
        val pricing = server { exchange -> exchange.respond(200, "{\"currency\":\"INR\",\"baseMinor\":2500,\"saleMinor\":1999,\"version\":7}") }
        val inventory = server { exchange -> exchange.respond(200, "[{\"available\":2},{\"available\":3}]") }
        try {
            assertEquals(PriceSnapshot(1999, "INR", "7"), PricingClient(url(pricing)).current("product-1", "variant-1", "INR"))
            assertEquals(5, InventoryClient(url(inventory)).available("variant-1"))
        } finally {
            pricing.stop(0)
            inventory.stop(0)
        }
    }

    @Test
    fun `pricing client uses base price when sale is absent`() {
        val pricing = server { exchange -> exchange.respond(200, "{\"currency\":\"USD\",\"baseMinor\":2500,\"version\":1}") }
        try {
            assertEquals(PriceSnapshot(2500, "USD", "1"), PricingClient(url(pricing)).current("product-1", "variant-1", "USD"))
        } finally {
            pricing.stop(0)
        }
    }

    @Test
    fun `clients map not found dependency and malformed responses`() {
        val notFound = server { exchange -> exchange.respond(404, "missing") }
        try {
            assertFailsWith<ApiException> { PricingClient(url(notFound)).current("p", "v", "INR") }
        } finally {
            notFound.stop(0)
        }

        val failedPricing = server { exchange -> exchange.respond(500, "failed") }
        val malformedPricing = server { exchange -> exchange.respond(200, "not-json") }
        val failedInventory = server { exchange -> exchange.respond(500, "failed") }
        val malformedInventory = server { exchange -> exchange.respond(200, "not-json") }
        try {
            assertFailsWith<ApiException> { PricingClient(url(failedPricing)).current("p", "v", "INR") }
            assertFailsWith<ApiException> { PricingClient(url(malformedPricing)).current("p", "v", "INR") }
            assertFailsWith<ApiException> { InventoryClient(url(failedInventory)).available("v") }
            assertFailsWith<ApiException> { InventoryClient(url(malformedInventory)).available("v") }
        } finally {
            failedPricing.stop(0)
            malformedPricing.stop(0)
            failedInventory.stop(0)
            malformedInventory.stop(0)
        }
    }

    @Test
    fun `pricing and inventory clients reject redirect-range status codes`() {
        val redirectPricing = server { exchange -> exchange.respond(301, "redirect") }
        val redirectInventory = server { exchange -> exchange.respond(302, "redirect") }
        try {
            assertFailsWith<ApiException> { PricingClient(url(redirectPricing)).current("p", "v", "INR") }
            assertFailsWith<ApiException> { InventoryClient(url(redirectInventory)).available("v") }
        } finally {
            redirectPricing.stop(0)
            redirectInventory.stop(0)
        }
    }

    @Test
    fun `price and inventory dtos implement value equality including optional sale price`() {
        val withSale = PriceDto("INR", 2500, 1999, 7)
        val sameWithSale = PriceDto("INR", 2500, 1999, 7)
        val withoutSale = PriceDto("INR", 2500, null, 7)
        val sameWithoutSale = PriceDto("INR", 2500, version = 7)

        assertEquals(withSale, sameWithSale)
        assertEquals(withSale.hashCode(), sameWithSale.hashCode())
        assertEquals(withoutSale, sameWithoutSale)
        assertNotEquals(withSale, withoutSale)
        assertNotEquals(withSale, withSale.copy(currency = "USD"))
        assertNotEquals(withSale, withSale.copy(baseMinor = 1))
        assertNotEquals(withSale, withSale.copy(version = 8))
        assertEquals("INR", withSale.copy().currency)
        assertTrue(withSale.toString().contains("2500"))

        val inventoryA = InventoryDto(5)
        val inventoryB = InventoryDto(5)
        val inventoryC = InventoryDto(6)
        assertEquals(inventoryA, inventoryB)
        assertEquals(inventoryA.hashCode(), inventoryB.hashCode())
        assertNotEquals(inventoryA, inventoryC)
        assertTrue(inventoryA.toString().contains("5"))
    }

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
