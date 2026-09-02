package com.ecommerce.refund

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
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

class RefundRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))

    @Test
    fun `refund routes enforce authentication, idempotency, ownership, and admin permissions`() = testApplication {
        val store = FakeRefundStore()
        application { installRoutes(store) }
        val request = json.encodeToString(RefundRequest("order-1", "payment-1", 1_000, "INR", RefundType.FULL, "customer request"))

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/refunds") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/refunds") { auth(token()); contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/refunds") { auth(token()); header("Idempotency-Key", "refund-key-1"); contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals("user-1", store.createdUser)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/refunds") { auth(token()) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/refunds/missing") { auth(token()) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/refunds/rfd-1/decision") { auth(token()); contentType(ContentType.Application.Json); setBody("{\"approve\":false}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/refunds/rfd-1/decision") { auth(token("ADMIN_REFUND_APPROVE")); contentType(ContentType.Application.Json); setBody("{\"approve\":true}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/refunds/rfd-1/decision") { auth(token(roles = listOf("FINANCE"))); contentType(ContentType.Application.Json); setBody("{\"approve\":true}") }.status)
    }

    @Test
    fun `internal decision requires token and dependency failures are mapped`() = testApplication {
        val store = FakeRefundStore()
        application { installRoutes(store) }
        val body = "{\"approve\":false,\"reason\":\"not eligible\"}"

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/internal/refunds/rfd-1/decision") { header("X-Internal-Service-Token", "wrong"); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/internal/refunds/rfd-1/decision") { header("X-Internal-Service-Token", "internal"); contentType(ContentType.Application.Json); setBody(body) }.status)

        store.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "refund store unavailable", 503, retryable = true)
        val response = client.get("/api/v1/refunds") { auth(token()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("DEPENDENCY_UNAVAILABLE"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: RefundStore) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureRefundRoutes(store, verifier, "internal") }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(value: String) = header(HttpHeaders.Authorization, "Bearer $value")

    private fun token(vararg permissions: String, roles: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"user-1\",\"roles\":[${roles.joinToString { "\"$it\"" }}],\"permissions\":[${permissions.joinToString { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeRefundStore : RefundStore {
        var createdUser: String? = null
        var failure: ApiException? = null
        private val refund = RefundResponse("rfd-1", "user-1", "order-1", "payment-1", 1_000, "INR", RefundType.FULL, RefundStatus.REQUESTED, "customer request", emptyList(), null, "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z")
        override fun create(userId: String, request: RefundRequest, key: String, correlation: String): RefundResponse { createdUser = userId; return refund }
        override fun getOwned(userId: String, id: String): RefundResponse? = if (id == refund.id && userId == refund.userId) refund else null
        override fun listOwned(userId: String): List<RefundResponse> { failure?.let { throw it }; return if (userId == refund.userId) listOf(refund) else emptyList() }
        override fun approve(id: String, decision: RefundDecision, actor: String, correlation: String): RefundResponse = refund.copy(status = if (decision.approve) RefundStatus.COMPLETED else RefundStatus.REJECTED)
    }
}
