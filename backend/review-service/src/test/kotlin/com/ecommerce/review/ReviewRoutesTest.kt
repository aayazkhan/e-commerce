package com.ecommerce.review

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.header
import io.ktor.client.request.patch
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

class ReviewRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))
    private val review = ReviewResponse("review-1", "user-1", ReviewTarget.PRODUCT, "product-1", "order-1", 5, "Great", "Excellent", true, ReviewStatus.PUBLISHED, 2, "2026-08-21T00:00:00Z")

    @Test
    fun `review routes cover creation browsing votes reports and moderation permissions`() = testApplication {
        val store = FakeReviewStore()
        application { installRoutes(store) }
        val request = json.encodeToString(ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 5, "Great", "Excellent"))

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/reviews") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/reviews") { auth(token()); contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/reviews/products/product-1?cursor=2&limit=5").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/reviews/sellers/seller-1").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/reviews/product/product-1/aggregate").status)
        assertEquals(HttpStatusCode.InternalServerError, client.get("/api/v1/reviews/unknown/product-1/aggregate").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/reviews/review-1/helpful").status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/reviews/review-1/helpful") { auth(token()) }.status)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/reviews/review-1/report") { auth(token()); contentType(ContentType.Application.Json); setBody("{\"reason\":\"abuse\"}") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/admin/reviews/review-1/moderation") { auth(token()); contentType(ContentType.Application.Json); setBody("{\"status\":\"HIDDEN\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/admin/reviews/review-1/moderation") { auth(token("ADMIN_REVIEW_MODERATE")); contentType(ContentType.Application.Json); setBody("{\"status\":\"HIDDEN\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/admin/reviews/review-1/moderation") { auth(token(roles = listOf("SUPPORT"))); contentType(ContentType.Application.Json); setBody("{\"status\":\"PUBLISHED\"}") }.status)
    }

    @Test
    fun `review dependency failure maps to typed error`() = testApplication {
        val store = FakeReviewStore().also { it.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "review store unavailable", 503, retryable = true) }
        application { installRoutes(store) }
        val response = client.get("/api/v1/reviews/products/product-1")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("DEPENDENCY_UNAVAILABLE"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: ReviewStore) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureReviewRoutes(store, verifier) }
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

    private inner class FakeReviewStore : ReviewStore {
        var failure: ApiException? = null
        override fun create(userId: String, request: ReviewRequest): ReviewResponse = review
        override fun list(target: ReviewTarget, targetId: String, cursor: Int, limit: Int): ReviewPage { failure?.let { throw it }; return ReviewPage(listOf(review), "20") }
        override fun aggregate(target: ReviewTarget, targetId: String) = RatingAggregate(target, targetId, 1, 5.0)
        override fun vote(userId: String, reviewId: String) {}
        override fun report(userId: String, reviewId: String, request: ReportRequest) {}
        override fun moderate(reviewId: String, request: ModerationRequest, actor: String) = review.copy(status = request.status)
    }
}
