package com.ecommerce.promotion

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromotionRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))

    @Test
    fun `quote cache and redemption routes enforce credentials and idempotency`() = testApplication {
        val store = FakePromotionStore()
        val cache = FakePromotionCache()
        application { installRoutes(store, cache) }
        val request = json.encodeToString(PromotionCalculateRequest(lines = listOf(PromotionLine("product-1", "variant-1", quantity = 2, unitPriceMinor = 500)), couponCode = "SAVE10"))

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/promotions/quote") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/promotions/quote") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(1, store.calculateCalls)
        assertEquals(1, cache.puts)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/internal/promotions?sellerId=seller-1") { header("X-Internal-Service-Token", "wrong") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/internal/promotions") { header("X-Internal-Service-Token", "internal") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/internal/promotions?sellerId=seller-1&limit=3") { header("X-Internal-Service-Token", "internal") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/internal/promotions?sellerId=seller-1&limit=not-a-number") { header("X-Internal-Service-Token", "internal") }.status)
        assertEquals(50, store.lastLimit)

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/promotions/apply") { contentType(ContentType.Application.Json); setBody("{\"request\":$request}") }.status)
        val apply = "{\"request\":$request,\"orderId\":\"order-1\"}"
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/promotions/apply") { auth(token()); contentType(ContentType.Application.Json); setBody(apply) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/promotions/apply") { auth(token()); header("Idempotency-Key", "redemption-1"); contentType(ContentType.Application.Json); setBody(apply) }.status)
        assertEquals("redemption-1", store.lastKey)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/promotions/apply") { auth(token()); header("Idempotency-Key", " "); contentType(ContentType.Application.Json); setBody(apply) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/internal/promotions/apply") { header("X-Internal-Service-Token", "internal"); header("Idempotency-Key", "internal-redemption"); contentType(ContentType.Application.Json); setBody("{\"userId\":\"user-1\",\"request\":$request}") }.status)
        cache.forced = "not-json"
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/promotions/validate") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(2, store.calculateCalls)
    }

    @Test
    fun `promotion lifecycle and admin permissions call exact operations`() = testApplication {
        val store = FakePromotionStore()
        application { installRoutes(store, FakePromotionCache()) }
        val user = token()
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/internal/promotions/redemptions/r-1/commit") { header("X-Internal-Service-Token", "internal") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/internal/promotions/redemptions/r-1/commit") { header("X-Internal-Service-Token", "internal"); header("X-Actor-Id", "user-1") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/internal/promotions/redemptions/r-1/release") { header("X-Internal-Service-Token", "internal"); header("X-Actor-Id", "user-1") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/promotions/remove") { auth(user); contentType(ContentType.Application.Json); setBody("{\"redemptionId\":\"r-1\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/promotions/redemptions/r-1/release") { auth(user) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/promotions/redemptions/r-1/commit") { auth(user) }.status)

        val promotion = json.encodeToString(PromotionRequest("Sale", PromotionType.FIXED_AMOUNT, startAt = "2026-08-21T00:00:00Z", fixedAmountMinor = 100))
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/promotions") { auth(token()); contentType(ContentType.Application.Json); setBody(promotion) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/admin/promotions") { auth(token("PROMOTION_CREATE")); contentType(ContentType.Application.Json); setBody(promotion) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/admin/promotions") { auth(tokenWithRole("ADMIN")); contentType(ContentType.Application.Json); setBody(promotion) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/admin/promotions") { auth(tokenWithRole("SUPER_ADMIN")); contentType(ContentType.Application.Json); setBody(promotion) }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/admin/promotions/p-1") { auth(token("PROMOTION_UPDATE")); contentType(ContentType.Application.Json); setBody(promotion) }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/admin/promotions/p-1") { auth(token("PROMOTION_DELETE")) }.status)

        val coupon = json.encodeToString(CouponRequest("p-1", "SAVE10"))
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/admin/coupons") { auth(token("COUPON_CREATE")); contentType(ContentType.Application.Json); setBody(coupon) }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/admin/coupons/c-1") { auth(token("COUPON_UPDATE")); contentType(ContentType.Application.Json); setBody(json.encodeToString(CouponUpdateRequest("ACTIVE"))) }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/admin/coupons/c-1") { auth(token("COUPON_DELETE")) }.status)
        assertTrue(store.operations.containsAll(listOf("create", "update", "archive", "createCoupon", "updateCoupon", "disableCoupon")))
    }

    @Test
    fun `internal routes reject a blank configured token`() = testApplication {
        application { installRoutes(FakePromotionStore(), FakePromotionCache(), internalToken = " ") }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/internal/promotions?sellerId=seller-1") {
            header("X-Internal-Service-Token", " ")
        }.status)
    }

    @Test
    fun `promotion dependency failure maps to typed error`() = testApplication {
        val store = FakePromotionStore().also { it.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "promotion store unavailable", 503, retryable = true) }
        application { installRoutes(store, FakePromotionCache()) }
        val request = json.encodeToString(PromotionCalculateRequest(lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 10))))

        val response = client.post("/api/v1/promotions/validate") { contentType(ContentType.Application.Json); setBody(request) }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("DEPENDENCY_UNAVAILABLE"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: PromotionStore, cache: PromotionCache, internalToken: String = "internal") {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configurePromotionRoutes(store, cache, verifier, internalToken) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(value: String) = header(HttpHeaders.Authorization, "Bearer $value")

    private fun token(vararg permissions: String): String = signedToken(emptyList(), permissions.toList())

    private fun tokenWithRole(role: String): String = signedToken(listOf(role), emptyList())

    private fun signedToken(roles: List<String>, permissions: List<String>): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"user-1\",\"roles\":[${roles.joinToString { "\"$it\"" }}],\"permissions\":[${permissions.joinToString { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakePromotionCache : PromotionCache {
        private val values = mutableMapOf<String, String>()
        var puts = 0
        var forced: String? = null
        override fun get(key: String) = forced ?: values[key]
        override fun put(key: String, value: String, ttl: Duration) { values[key] = value; puts++ }
    }

    private class FakePromotionStore : PromotionStore {
        var calculateCalls = 0
        var lastKey: String? = null
        var lastLimit: Int? = null
        var failure: ApiException? = null
        val operations = mutableListOf<String>()
        private val promotion = PromotionResponse("p-1", "Sale", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, "2026-08-20T00:00:00Z", null, "INR", 0, null, 1_000, null, emptyList(), emptyList(), emptyList(), null, 0, null, StackPolicy.NO_STACK, 1, 1)
        private val quote = PromotionQuote("p-1", "SAVE10", "INR", 100, false)
        private val redemption = RedemptionResponse("r-1", "p-1", "SAVE10", "user-1", "order-1", 100, "INR", RedemptionStatus.RESERVED, quote, "2026-08-21T00:00:00Z")
        override fun listForSeller(sellerId: String, limit: Int) = listOf(promotion).also { lastLimit = limit }
        override fun create(input: PromotionRequest, actorId: String, correlationId: String) = promotion.also { operations += "create" }
        override fun update(id: String, input: PromotionRequest, actorId: String, correlationId: String) = promotion.also { operations += "update" }
        override fun archive(id: String, actorId: String, correlationId: String) = promotion.copy(status = PromotionStatus.ARCHIVED).also { operations += "archive" }
        override fun createCoupon(input: CouponRequest, actorId: String, correlationId: String) = CouponResponse("c-1", input.promotionId, input.code, "ACTIVE", input.usageLimit, 0, input.perUserLimit).also { operations += "createCoupon" }
        override fun updateCoupon(id: String, input: CouponUpdateRequest, actorId: String, correlationId: String) = CouponResponse("c-1", "p-1", "SAVE10", input.status, input.usageLimit, 0, input.perUserLimit).also { operations += "updateCoupon" }
        override fun disableCoupon(id: String, actorId: String, correlationId: String) = CouponResponse("c-1", "p-1", "SAVE10", "DISABLED", null, 0, null).also { operations += "disableCoupon" }
        override fun calculate(userId: String?, input: PromotionCalculateRequest): PromotionQuote { failure?.let { throw it }; calculateCalls++; return quote }
        override fun apply(userId: String, input: PromotionCalculateRequest, key: String, orderId: String?, correlationId: String): RedemptionResponse { lastKey = key; return redemption }
        override fun transition(userId: String, id: String, target: RedemptionStatus, correlationId: String): RedemptionResponse = redemption.copy(status = target)
    }
}
