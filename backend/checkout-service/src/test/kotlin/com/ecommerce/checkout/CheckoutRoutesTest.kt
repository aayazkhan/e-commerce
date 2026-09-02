package com.ecommerce.checkout

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

class CheckoutRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))

    @Test
    fun `checkout routes enforce authentication, idempotency, ownership and workflow execution`() = testApplication {
        val store = FakeCheckoutStore()
        val workflow = FakeCheckoutWorkflow()
        application { installRoutes(store, workflow) }

        val missingAuth = client.post("/api/v1/checkout/validate") { contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.Unauthorized, missingAuth.status)
        val auth = token()
        val validation = client.post("/api/v1/checkout/validate") { header(HttpHeaders.Authorization, "Bearer $auth"); contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.OK, validation.status)
        assertTrue(validation.bodyAsText().contains("valid"))
        assertEquals("user-1", workflow.validatedUser)

        val noKey = client.post("/api/v1/checkout") { header(HttpHeaders.Authorization, "Bearer $auth"); contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.BadRequest, noKey.status)
        val created = client.post("/api/v1/checkout") { header(HttpHeaders.Authorization, "Bearer $auth"); header("Idempotency-Key", "checkout-key-1"); contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals("checkout-key-1", store.lastKey)
        assertEquals("internal-secret", workflow.lastInternalToken)
        assertTrue(created.bodyAsText().contains("COMPLETED"))

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/checkout/chk-1") { header(HttpHeaders.Authorization, "Bearer $auth") }.status)
        val missing = client.get("/api/v1/checkout/missing") { header(HttpHeaders.Authorization, "Bearer $auth") }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
        val otherOwner = client.get("/api/v1/checkout/chk-1") { header(HttpHeaders.Authorization, "Bearer ${token("user-2")}") }
        assertEquals(HttpStatusCode.NotFound, otherOwner.status)
        assertTrue(otherOwner.bodyAsText().contains("NOT_FOUND"))

        val retryNoKey = client.post("/api/v1/checkout/chk-1/retry") { header(HttpHeaders.Authorization, "Bearer $auth"); contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.BadRequest, retryNoKey.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/checkout/chk-1/retry") { header(HttpHeaders.Authorization, "Bearer $auth"); header("Idempotency-Key", "checkout-key-2"); contentType(ContentType.Application.Json); setBody(requestBody()) }.status)
        assertEquals(2, workflow.executeCalls)
    }

    @Test
    fun `checkout routes map invalid tokens and application failures to stable errors`() = testApplication {
        val store = FakeCheckoutStore()
        val workflow = FakeCheckoutWorkflow()
        application { installRoutes(store, workflow) }

        val invalidToken = client.get("/api/v1/checkout/chk-1") { header(HttpHeaders.Authorization, "Bearer not-a-jwt") }
        assertEquals(HttpStatusCode.Unauthorized, invalidToken.status)
        assertTrue(invalidToken.bodyAsText().contains("AUTHENTICATION_REQUIRED"))

        val auth = token()
        workflow.validateFailure = ApiException(ErrorCode.CONFLICT, "Cart changed", 409)
        val validationFailure = client.post("/api/v1/checkout/validate") {
            header(HttpHeaders.Authorization, "Bearer $auth")
            contentType(ContentType.Application.Json)
            setBody(requestBody())
        }
        assertEquals(HttpStatusCode.Conflict, validationFailure.status)
        assertTrue(validationFailure.bodyAsText().contains("Cart changed"))

        workflow.validateFailure = null
        store.startFailure = ApiException(ErrorCode.CONFLICT, "Duplicate checkout", 409)
        val startFailure = client.post("/api/v1/checkout") {
            header(HttpHeaders.Authorization, "Bearer $auth")
            header("Idempotency-Key", "duplicate-key")
            contentType(ContentType.Application.Json)
            setBody(requestBody())
        }
        assertEquals(HttpStatusCode.Conflict, startFailure.status)
        assertTrue(startFailure.bodyAsText().contains("Duplicate checkout"))

        store.startFailure = null
        workflow.executeFailure = IllegalStateException("workflow crashed")
        val unexpected = client.post("/api/v1/checkout") {
            header(HttpHeaders.Authorization, "Bearer $auth")
            header("Idempotency-Key", "crash-key")
            contentType(ContentType.Application.Json)
            setBody(requestBody())
        }
        assertEquals(HttpStatusCode.InternalServerError, unexpected.status)
        assertTrue(unexpected.bodyAsText().contains("INTERNAL_ERROR"))

        workflow.executeFailure = null
        val malformed = client.post("/api/v1/checkout/chk-1/retry") {
            header(HttpHeaders.Authorization, "Bearer $auth")
            header("Idempotency-Key", "malformed-key")
            contentType(ContentType.Application.Json)
            setBody("{not-json")
        }
        assertEquals(HttpStatusCode.InternalServerError, malformed.status)
        assertTrue(malformed.bodyAsText().contains("INTERNAL_ERROR"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: CheckoutStore, workflow: CheckoutWorkflow) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureCheckoutRoutes(store, workflow, verifier, "internal-secret") }
    }

    private fun requestBody() = json.encodeToString(CheckoutRequest("cart-1", "address-1", null, CheckoutShippingMethod.EXPRESS, "token", "HTTP", "INR", "SAVE10"))

    private fun token(subject: String = "user-1"): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"$subject\",\"roles\":[],\"permissions\":[],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeCheckoutStore : CheckoutStore {
        var lastKey: String? = null
        var startFailure: ApiException? = null
        override fun start(userId: String, request: CheckoutRequest, key: String): CheckoutResponse {
            startFailure?.let { throw it }
            lastKey = key
            return sample
        }
        override fun get(id: String): CheckoutResponse? = sample.takeIf { id == it.checkoutId }
        override fun getOwned(userId: String, id: String): CheckoutResponse? = sample.takeIf { id == it.checkoutId && userId == "user-1" }
        override fun checkpoint(id: String, status: CheckoutStatus, step: CheckoutStep, reservationId: String?, orderId: String?, promotionId: String?, paymentId: String?, paymentStatus: String?, paymentSecret: String?, shipmentId: String?, totals: CheckoutTotals?, error: String?) = Unit
        override fun fail(id: String, message: String, recoverable: Boolean) = Unit
    }

    private class FakeCheckoutWorkflow : CheckoutWorkflow {
        var validatedUser: String? = null
        var executeCalls = 0
        var lastInternalToken: String? = null
        var validateFailure: Throwable? = null
        var executeFailure: Throwable? = null
        override suspend fun validate(userId: String, bearer: String, request: CheckoutRequest): CheckoutValidationResponse {
            validateFailure?.let { throw it }
            validatedUser = userId
            return CheckoutValidationResponse(true, CheckoutTotals(1_000, 0, 100, 50, 180, 1_130, request.currency))
        }
        override suspend fun execute(initial: CheckoutResponse, request: CheckoutRequest, userId: String, bearer: String, correlation: String, internalToken: String): CheckoutResponse {
            executeFailure?.let { throw it }
            executeCalls++
            lastInternalToken = internalToken
            return sample.copy(status = CheckoutStatus.COMPLETED, currentStep = CheckoutStep.COMPLETE)
        }
    }

    private companion object {
        val sample = CheckoutResponse("chk-1", CheckoutStatus.CREATED, CheckoutStep.VALIDATE_CART, createdAt = "2026-08-20T00:00:00Z", updatedAt = "2026-08-20T00:00:00Z")
    }
}
