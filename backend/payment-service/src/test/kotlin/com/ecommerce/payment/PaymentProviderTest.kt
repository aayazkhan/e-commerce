package com.ecommerce.payment

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaymentProviderTest {
    @Test
    fun `cod provider returns deterministic payment and refund results`() {
        val provider = CodPaymentProvider()
        val request = ProviderCreateRequest("payment-1", "order-1", 1000, "INR", "cod", null)
        assertEquals(PaymentProviderName.COD, provider.name)
        assertEquals(ProviderPayment("cod-payment-1", PaymentStatus.AUTHORIZED), provider.create(request))
        assertEquals(ProviderPayment("provider-1", PaymentStatus.AUTHORIZED), provider.query("provider-1"))
        assertEquals(ProviderRefund("cod-refund-key", PaymentStatus.REFUNDED), provider.refund("provider-1", 100, "INR", "key"))
        assertFalse(provider.verifyWebhook("body", "signature"))
    }

    @Test
    fun `http payment provider maps provider statuses and webhook signatures`() {
        val server = server { exchange ->
            val response = when {
                exchange.requestMethod == "GET" && exchange.requestURI.path.endsWith("/requires") -> "{\"id\":\"requires\",\"status\":\"REQUIRES_PAYMENT_METHOD\"}"
                exchange.requestMethod == "GET" && exchange.requestURI.path.endsWith("/requires-action") -> "{\"id\":\"requires-action\",\"status\":\"REQUIRES_ACTION\"}"
                exchange.requestMethod == "GET" -> "{\"providerPaymentId\":\"provider-1\",\"status\":\"AUTHORISED\"}"
                exchange.requestURI.path.endsWith("/refund") -> "{\"id\":\"refund-1\",\"status\":\"CANCELLED\"}"
                else -> "{\"id\":\"provider-1\",\"status\":\"SUCCEEDED\",\"clientSecret\":\"secret\"}"
            }
            exchange.respond(200, response)
        }
        try {
            val provider = HttpPaymentProvider("http://127.0.0.1:${server.address.port}", "api-key", "webhook-secret")
            val create = provider.create(ProviderCreateRequest("payment-1", "order-1", 1000, "INR", "token", "https://shop.test/return"))
            assertEquals(ProviderPayment("provider-1", PaymentStatus.CAPTURED, "secret"), create)
            assertEquals(ProviderPayment("provider-1", PaymentStatus.AUTHORIZED), provider.query("provider-1"))
            assertEquals(ProviderPayment("requires", PaymentStatus.REQUIRES_ACTION), provider.query("requires"))
            assertEquals(ProviderPayment("requires-action", PaymentStatus.REQUIRES_ACTION), provider.query("requires-action"))
            assertEquals(ProviderRefund("refund-1", PaymentStatus.FAILED), provider.refund("provider-1", 100, "INR", "refund-key"))

            val signature = hmac("webhook-secret", "payload")
            assertTrue(provider.verifyWebhook("payload", signature))
            assertTrue(provider.verifyWebhook("payload", "sha256=$signature"))
            assertFalse(provider.verifyWebhook("payload", "$signature-x"))
            assertFalse(provider.verifyWebhook("payload", ""))
            assertFalse(provider.verifyWebhook("payload", null))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http payment provider rejects missing configuration and invalid response`() {
        val request = ProviderCreateRequest("payment-1", "order-1", 1000, "INR", "token", null)
        assertFailsWith<ApiException> { HttpPaymentProvider("", "api-key", "secret").create(request) }
        assertFailsWith<ApiException> { HttpPaymentProvider("http://127.0.0.1:1", "", "secret").create(request) }
        assertFailsWith<ApiException> { HttpPaymentProvider("http://127.0.0.1:1", "api-key", "secret").create(request) }
    }

    @Test
    fun `http provider uses fallback identifiers statuses and rejects non success responses`() {
        val server = server { exchange ->
            val response = when {
                exchange.requestURI.path.endsWith("/refund") -> "{}"
                exchange.requestURI.path.endsWith("/paid") -> "{\"id\":\"paid\",\"status\":\"PAID\"}"
                exchange.requestURI.path.endsWith("/unknown") -> "{\"id\":\"unknown\",\"status\":\"PROCESSING\"}"
                else -> "{\"providerPaymentId\":\"provider-fallback\"}"
            }
            exchange.respond(200, response)
        }
        try {
            val provider = HttpPaymentProvider("http://127.0.0.1:${server.address.port}", "api-key", "")
            val request = ProviderCreateRequest("payment-1", "order-1", 1000, "INR", "token", null)

            assertEquals(ProviderPayment("provider-fallback", PaymentStatus.PROCESSING), provider.create(request))
            assertEquals(ProviderPayment("paid", PaymentStatus.CAPTURED), provider.query("paid"))
            assertEquals(ProviderPayment("unknown", PaymentStatus.PROCESSING), provider.query("unknown"))
            assertEquals(ProviderRefund(null, PaymentStatus.REFUNDED), provider.refund("provider-1", 100, "INR", "refund-key"))
            assertFalse(provider.verifyWebhook("payload", "signature"))
        } finally {
            server.stop(0)
        }

        val rejected = server { it.respond(502, "down") }
        try {
            val provider = HttpPaymentProvider("http://127.0.0.1:${rejected.address.port}", "api-key", "secret")
            val error = assertFailsWith<ApiException> { provider.query("provider-1") }
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode)
            assertEquals(502, error.statusCode)
        } finally {
            rejected.stop(0)
        }
    }

    @Test
    fun `http provider rejects a successful response without a payment identifier`() {
        val server = server { it.respond(200, "{}") }
        try {
            val provider = HttpPaymentProvider("http://127.0.0.1:${server.address.port}", "api-key", "secret")
            val error = assertFailsWith<ApiException> {
                provider.query("provider-1")
            }

            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode)
            assertEquals(502, error.statusCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `payu provider builds a signed hosted-checkout form and verifies its own reverse hash`() {
        val provider = PayuPaymentProvider("test-key", "test-salt", "https://test.payu.in", "https://payments.local/callback", "https://payments.local/callback")
        val request = ProviderCreateRequest("payment-1", "order-1", 54999, "INR", "payu", null, "Jane Doe", "jane@example.com", "9999999999", "checkout-1")

        val result = provider.create(request)
        assertEquals(PaymentStatus.REQUIRES_ACTION, result.status)
        val fields = kotlinx.serialization.json.Json.decodeFromString<PayuFormFields>(result.clientSecret!!)
        assertEquals("test-key", fields.key)
        assertEquals("549.99", fields.amount)
        assertEquals("checkout-1", fields.udf1)
        assertEquals("https://test.payu.in/_payment", fields.actionUrl)

        val requestHash = sha512(
            (listOf("test-key", fields.txnid, "549.99", "order-1", "Jane Doe", "jane@example.com", "checkout-1") + List(9) { "" } + "test-salt")
                .joinToString("|"),
        )
        assertEquals(requestHash, fields.hash)

        // The reverse hash PayU would send back on a successful callback for this same txn.
        val callbackFields = mapOf(
            "status" to "success",
            "txnid" to fields.txnid,
            "amount" to "549.99",
            "productinfo" to "order-1",
            "firstname" to "Jane Doe",
            "email" to "jane@example.com",
            "udf1" to "checkout-1",
            "hash" to sha512(
                (listOf("test-salt", "success") + List(9) { "" } + listOf("checkout-1", "jane@example.com", "Jane Doe", "order-1", "549.99", fields.txnid, "test-key"))
                    .joinToString("|"),
            ),
        )
        assertTrue(provider.verifyResponseHash(callbackFields))
        assertFalse(provider.verifyResponseHash(callbackFields + ("amount" to "1.00")))
        assertFalse(provider.verifyResponseHash(callbackFields - "hash"))
    }

    @Test
    fun `payu provider rejects create when unconfigured`() {
        val request = ProviderCreateRequest("payment-1", "order-1", 1000, "INR", "payu", null)
        val error = assertFailsWith<ApiException> { PayuPaymentProvider("", "", "https://test.payu.in", "", "").create(request) }
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode)
    }

    private fun sha512(value: String) = java.security.MessageDigest.getInstance("SHA-512").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

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
