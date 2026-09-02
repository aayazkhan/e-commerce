package com.ecommerce.payment

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))

    @Test
    fun `customer and internal payment routes enforce permission token and idempotency`() = testApplication {
        val store = FakePaymentStore()
        application { installRoutes(store) }
        val request = json.encodeToString(PaymentCreateRequest("order-1", 1_000, "INR", PaymentProviderName.COD, "cash"))
        val user = token()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/payments") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/payments") { auth(user); contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/payments") { auth(token("PAYMENT_CREATE")); contentType(ContentType.Application.Json); setBody(request) }.status)
        val created = client.post("/api/v1/payments") { auth(token("PAYMENT_CREATE")); header("Idempotency-Key", "pay-key"); contentType(ContentType.Application.Json); setBody(request) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("user-1", store.createdUser)

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/internal/payments") { header("X-Internal-Service-Token", "wrong"); contentType(ContentType.Application.Json); setBody("{\"userId\":\"user-1\",\"request\":$request}") }.status)
        val envelope = "{\"userId\":\"user-1\",\"request\":$request}"
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/internal/payments") { header("X-Internal-Service-Token", "internal"); contentType(ContentType.Application.Json); setBody(envelope) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/internal/payments") { header("X-Internal-Service-Token", "internal"); header("Idempotency-Key", "internal-key"); contentType(ContentType.Application.Json); setBody(envelope) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/internal/payments/payment-1") { header("X-Internal-Service-Token", "internal") }.status)
    }

    @Test
    fun `payment ownership refund webhook and admin branches are asserted`() = testApplication {
        val store = FakePaymentStore()
        val webhooks = FakeWebhookVerifier()
        application { installRoutes(store, webhooks) }

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/payments/payment-1") { auth(token("PAYMENT_READ")) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/payments/missing") { auth(token("PAYMENT_READ")) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/internal/payments/payment-1/refund") { header("X-Internal-Service-Token", "internal"); contentType(ContentType.Application.Json); setBody(json.encodeToString(RefundPaymentRequest(500, "INR", "refund-key"))) }.status)

        val webhook = json.encodeToString(PaymentWebhookRequest("event-1", "provider-payment-1", PaymentStatus.CAPTURED, 1_000, "INR"))
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/payments/webhooks/nope") { contentType(ContentType.Application.Json); setBody(webhook) }.status)
        webhooks.valid = false
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/payments/webhooks/COD") { header("X-Provider-Signature", "bad"); contentType(ContentType.Application.Json); setBody(webhook) }.status)
        webhooks.valid = true
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/payments/webhooks/COD") { header("X-Provider-Signature", "good"); contentType(ContentType.Application.Json); setBody(webhook) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/payments/payment-1") { auth(token()) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/payments/payment-1") { auth(token("ADMIN_PAYMENT_READ")) }.status)
        assertTrue(store.webhookSeen)
    }

    @Test
    fun `payment store failure remains a typed dependency error`() = testApplication {
        val store = FakePaymentStore().also { it.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "payment store unavailable", 503, retryable = true) }
        application { installRoutes(store) }

        val response = client.get("/api/v1/internal/payments/payment-1") { header("X-Internal-Service-Token", "internal") }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("DEPENDENCY_UNAVAILABLE"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: PaymentStore, webhooks: PaymentWebhookVerifier = FakeWebhookVerifier()) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configurePaymentRoutes(store, verifier, "internal", webhooks, json) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(value: String) = header(HttpHeaders.Authorization, "Bearer $value")

    private fun token(vararg permissions: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"user-1\",\"roles\":[],\"permissions\":[${permissions.joinToString { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeWebhookVerifier : PaymentWebhookVerifier {
        var valid = true
        override fun verify(provider: PaymentProviderName, body: String, signature: String?) = valid && signature == "good"
    }

    private class FakePaymentStore : PaymentStore {
        var createdUser: String? = null
        var failure: ApiException? = null
        var webhookSeen = false
        private val payment = PaymentResponse("payment-1", "order-1", "user-1", PaymentProviderName.COD, "provider-payment-1", PaymentStatus.CAPTURED, 1_000, "INR", 1, null, "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z")
        override fun create(userId: String, request: PaymentCreateRequest, key: String, correlationId: String): PaymentResponse { createdUser = userId; return payment }
        override fun getOwned(userId: String, id: String): PaymentResponse? { failure?.let { throw it }; return payment.takeIf { it.userId == userId && it.id == id } }
        override fun getInternal(id: String): PaymentResponse? { failure?.let { throw it }; return payment.takeIf { it.id == id } }
        override fun webhook(providerName: PaymentProviderName, request: PaymentWebhookRequest, correlationId: String): PaymentResponse { webhookSeen = true; return payment }
        override fun refund(id: String, userId: String?, request: RefundPaymentRequest, correlationId: String) = RefundPaymentResponse(id, request.amountMinor, PaymentStatus.REFUNDED, "refund-1", "2026-08-21T00:00:00Z")
    }
}
