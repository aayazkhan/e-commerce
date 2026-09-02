package com.ecommerce.shipping

import com.ecommerce.platform.error.ApiException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShippingProviderTest {
    private val address = ShippingAddress("Ayyaz", "+919999999999", "1 Main Road", city = "Mumbai", state = "MH", postalCode = "400001", country = "IN")

    @Test
    fun `http shipping provider maps quote shipment tracking and cancellation`() {
        val server = server { exchange ->
            val response = when {
                exchange.requestURI.path.endsWith("/quotes") -> "{\"id\":\"quote-1\",\"amountMinor\":149,\"expiresAt\":\"2026-08-20T11:00:00Z\"}"
                exchange.requestURI.path.endsWith("/cancel") -> "{\"providerShipmentId\":\"shipment-1\",\"status\":\"DELIVERED\",\"trackingNumber\":\"TRACK1\",\"carrier\":\"Carrier\"}"
                exchange.requestURI.path.endsWith("/unknown") -> "{\"id\":\"unknown\",\"status\":\"NEW_STATUS\"}"
                exchange.requestURI.path.endsWith("/missing") -> "{\"providerShipmentId\":\"shipment-2\"}"
                else -> "{\"id\":\"shipment-1\",\"status\":\"IN_TRANSIT\",\"trackingNumber\":\"TRACK1\",\"carrier\":\"Carrier\"}"
            }
            exchange.respond(200, response)
        }
        try {
            val provider = HttpShippingProvider("http://127.0.0.1:${server.address.port}", "api-key", "webhook-secret")
            val items = listOf(ShipmentItem("variant-1", 1))
            assertEquals("http", provider.name)
            assertEquals(ShippingQuote("quote-1", ShippingMethod.STANDARD, 149, "INR", "2026-08-20T11:00:00Z"), provider.quote(ShippingQuoteRequest(address, items, ShippingMethod.STANDARD, "INR")))
            assertEquals(ProviderShipment("shipment-1", ShipmentStatus.IN_TRANSIT, "TRACK1", "Carrier"), provider.create(ShipmentCreateRequest("order-1", "user-1", address, items, ShippingMethod.STANDARD, 149, "INR")))
            assertEquals(ProviderShipment("shipment-1", ShipmentStatus.IN_TRANSIT, "TRACK1", "Carrier"), provider.track("shipment-1"))
            assertEquals(ProviderShipment("shipment-1", ShipmentStatus.DELIVERED, "TRACK1", "Carrier"), provider.cancel("shipment-1"))
            assertEquals(ShipmentStatus.IN_TRANSIT, provider.track("unknown").status)
            assertEquals(ProviderShipment("shipment-2", ShipmentStatus.LABEL_CREATED, null, null), provider.track("missing"))

            val signature = hmac("webhook-secret", "payload")
            assertTrue(provider.verifyWebhook("payload", signature))
            assertTrue(provider.verifyWebhook("payload", "sha256=$signature"))
            assertFalse(provider.verifyWebhook("payload", "bad"))
            assertFalse(provider.verifyWebhook("payload", ""))
            assertFalse(provider.verifyWebhook("payload", null))
            assertFalse(HttpShippingProvider("http://127.0.0.1:${server.address.port}", "api-key", "").verifyWebhook("payload", signature))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `shipping provider rejects configuration transport and invalid response`() {
        val request = ShippingQuoteRequest(address, emptyList(), ShippingMethod.STANDARD, "INR")
        assertFailsWith<ApiException> { HttpShippingProvider("", "api-key", "secret").quote(request) }
        assertFailsWith<ApiException> { HttpShippingProvider("http://127.0.0.1:1", "", "secret").quote(request) }
        assertFailsWith<ApiException> { HttpShippingProvider("http://127.0.0.1:1", "api-key", "secret").quote(request) }

        val invalid = server { exchange -> exchange.respond(200, "{\"id\":\"quote-1\"}") }
        try {
            assertFailsWith<ApiException> { HttpShippingProvider("http://127.0.0.1:${invalid.address.port}", "api-key", "secret").quote(request) }
        } finally {
            invalid.stop(0)
        }

        val missingQuoteId = server { exchange -> exchange.respond(200, "{\"amountMinor\":149,\"expiresAt\":\"2026-08-20T11:00:00Z\"}") }
        try {
            assertFailsWith<ApiException> { HttpShippingProvider("http://127.0.0.1:${missingQuoteId.address.port}", "api-key", "secret").quote(request) }
        } finally {
            missingQuoteId.stop(0)
        }

        val missingQuoteExpiry = server { exchange -> exchange.respond(200, "{\"id\":\"quote-1\",\"amountMinor\":149}") }
        try {
            assertFailsWith<ApiException> { HttpShippingProvider("http://127.0.0.1:${missingQuoteExpiry.address.port}", "api-key", "secret").quote(request) }
        } finally {
            missingQuoteExpiry.stop(0)
        }

        val malformedAmount = server { exchange -> exchange.respond(200, "{\"id\":\"quote-1\",\"amountMinor\":\"not-a-number\",\"expiresAt\":\"2026-08-20T11:00:00Z\"}") }
        try {
            val error = assertFailsWith<ApiException> { HttpShippingProvider("http://127.0.0.1:${malformedAmount.address.port}", "api-key", "secret").quote(request) }
            assertEquals(502, error.statusCode)
        } finally {
            malformedAmount.stop(0)
        }

        val missingShipmentId = server { exchange -> exchange.respond(200, "{\"status\":\"IN_TRANSIT\"}") }
        try {
            assertFailsWith<ApiException> {
                HttpShippingProvider("http://127.0.0.1:${missingShipmentId.address.port}", "api-key", "secret").track("shipment-1")
            }
        } finally {
            missingShipmentId.stop(0)
        }

        val rejected = server { exchange -> exchange.respond(400, "rejected") }
        try {
            assertFailsWith<ApiException> { HttpShippingProvider("http://127.0.0.1:${rejected.address.port}", "api-key", "secret").quote(request) }
        } finally {
            rejected.stop(0)
        }
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.createContext("/") { exchange -> handler(exchange) }
        it.start()
    }

    private fun hmac(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
