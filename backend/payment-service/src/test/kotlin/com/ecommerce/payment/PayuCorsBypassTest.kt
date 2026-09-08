package com.ecommerce.payment

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduces exactly the wiring module() uses in production (see Application.kt's own comment
 * on its Setup-phase intercept): PayU's surl/furl POST to /api/v1/payments/payu/callback is a
 * real cross-origin browser navigation from PayU's own domain, which Ktor's Application-level
 * CORS plugin would otherwise reject outright. A route-scoped `install(CORS)` was tried first
 * and did NOT isolate the sibling callback route as expected -- this test locks in the
 * Setup-phase bypass that replaced it, against a minimal Application (not the full module(),
 * which needs a real database).
 */
class PayuCorsBypassTest {
    @Test
    fun `the Setup-phase bypass reaches the payu callback before CORS rejects a foreign origin`() = testApplication {
        val store = object : PaymentStore {
            override fun create(userId: String, request: PaymentCreateRequest, key: String, correlationId: String) = error("unused")
            override fun getOwned(userId: String, id: String) = error("unused")
            override fun getInternal(id: String) = error("unused")
            override fun getByProviderPaymentId(providerPaymentId: String) = null
            override fun webhook(providerName: PaymentProviderName, request: PaymentWebhookRequest, correlationId: String) = error("unused")
            override fun refund(id: String, userId: String?, request: RefundPaymentRequest, correlationId: String) = error("unused")
        }
        val payu = PayuPaymentProvider("key", "salt", "https://test.payu.in", "http://localhost:8092/api/v1/payments/payu/callback", "http://localhost:8092/api/v1/payments/payu/callback")

        application {
            intercept(ApplicationCallPipeline.Setup) {
                if (call.request.httpMethod == HttpMethod.Post && call.request.path() == "/api/v1/payments/payu/callback") {
                    call.handlePayuCallback(store, payu, "http://localhost:3002")
                    finish()
                }
            }
            install(CORS) { allowHost("localhost:3000") }
            routing { post("/api/v1/payments") { call.respond(HttpStatusCode.Created) } }
        }

        // A cross-origin POST from PayU's domain to a normal API route is still CORS-gated.
        val gated = client.post("/api/v1/payments") { header(HttpHeaders.Origin, "https://pgsim01.payu.in") }
        assertEquals(HttpStatusCode.Forbidden, gated.status)

        // The same cross-origin POST to the payu/callback route is NOT blocked by CORS -- only
        // this route's own hash check can reject it, and an invalid hash still resolves to a
        // redirect (never a 403).
        val noRedirectClient = client.config { followRedirects = false }
        val callback = noRedirectClient.post("/api/v1/payments/payu/callback") {
            header(HttpHeaders.Origin, "https://pgsim01.payu.in")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("status=success&txnid=txn-1&amount=10.00&udf1=checkout-1")
        }
        assertTrue(callback.status.value in 300..399)
        assertTrue(callback.headers[HttpHeaders.Location]?.contains("payment=invalid") == true)
    }

    @Test
    fun `a PayU convenience fee in the callback amount does not block the webhook update`() = testApplication {
        // PayU's hosted checkout can add its own convenience fee on top for some payment methods
        // (observed on card payments): the amount echoed back on the callback can legitimately
        // differ from what we originally charged. handlePayuCallback must use the amount WE
        // recorded (via getByProviderPaymentId), not PayU's, when calling store.webhook() --
        // otherwise PaymentRepository.webhook()'s amount-match anti-forgery check would reject a
        // genuine callback outright.
        val recorded = PaymentResponse("pay-1", "order-1", "user-1", PaymentProviderName.PAYU, "txn-1", PaymentStatus.REQUIRES_ACTION, 254999, "INR", 1, null, "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z")
        var webhookRequest: PaymentWebhookRequest? = null
        val store = object : PaymentStore {
            override fun create(userId: String, request: PaymentCreateRequest, key: String, correlationId: String) = error("unused")
            override fun getOwned(userId: String, id: String) = error("unused")
            override fun getInternal(id: String) = error("unused")
            override fun getByProviderPaymentId(providerPaymentId: String) = recorded.takeIf { providerPaymentId == "txn-1" }
            override fun webhook(providerName: PaymentProviderName, request: PaymentWebhookRequest, correlationId: String): PaymentResponse {
                webhookRequest = request
                return recorded.copy(status = PaymentStatus.CAPTURED)
            }
            override fun refund(id: String, userId: String?, request: RefundPaymentRequest, correlationId: String) = error("unused")
        }
        val merchantKey = "key"
        val merchantSalt = "salt"
        val payu = PayuPaymentProvider(merchantKey, merchantSalt, "https://test.payu.in", "http://localhost:8092/api/v1/payments/payu/callback", "http://localhost:8092/api/v1/payments/payu/callback")

        application {
            routing { post("/api/v1/payments/payu/callback") { call.handlePayuCallback(store, payu, "http://localhost:3002") } }
        }

        val hashInput = (listOf(merchantSalt, "success") + List(9) { "" } + listOf("checkout-1", "customer@example.com", "Checkout E2E", "order-1", "2715.77", "txn-1", merchantKey)).joinToString("|")
        val hash = java.security.MessageDigest.getInstance("SHA-512").digest(hashInput.toByteArray()).joinToString("") { "%02x".format(it) }
        val noRedirectClient = client.config { followRedirects = false }
        // 2715.77 (with PayU's fee) vs the 2549.99 we actually recorded -- this must still succeed.
        val response = noRedirectClient.post("/api/v1/payments/payu/callback") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("status=success&txnid=txn-1&amount=2715.77&productinfo=order-1&firstname=Checkout%20E2E&email=customer%40example.com&udf1=checkout-1&hash=$hash")
        }

        assertTrue(response.headers[HttpHeaders.Location]?.contains("payment=success") == true)
        assertEquals(254999L, webhookRequest?.amountMinor)
        assertEquals("INR", webhookRequest?.currency)
    }
}
