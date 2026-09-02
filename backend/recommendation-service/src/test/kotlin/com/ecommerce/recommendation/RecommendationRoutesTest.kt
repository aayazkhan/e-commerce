package com.ecommerce.recommendation

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `recommendation cache hit and deterministic fallback are observable`() = testApplication {
        val store = FakeRecommendationStore()
        val cache = FakeRecommendationCache()
        application { installRoutes(store, cache) }

        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/recommendations/events") { contentType(ContentType.Application.Json); setBody(json.encodeToString(BehaviorRequest("ProductViewed", "user-1", productId = "product-1"))) }.status)
        val first = client.get("/api/v1/recommendations?type=SIMILAR&userId=user-1&productId=product-1&limit=3")
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.bodyAsText().contains("rules"))
        assertEquals(1, store.recommendationCalls)
        val cached = client.get("/api/v1/recommendations?type=SIMILAR&userId=user-1&productId=product-1&limit=3")
        assertTrue(cached.bodyAsText().contains("cached"))
        assertEquals(1, store.recommendationCalls)

        store.empty = true
        val fallback = client.get("/api/v1/recommendations?type=TRENDING&limit=4")
        assertEquals(HttpStatusCode.OK, fallback.status)
        assertTrue(fallback.bodyAsText().contains("popular-fallback"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/recommendations/fallback?limit=2").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/recommendations?type=UNKNOWN").status)
    }

    @Test
    fun `recommendation dependency failure degrades to popular products`() = testApplication {
        val store = FakeRecommendationStore().also { it.failure = IllegalStateException("rules unavailable") }
        application { installRoutes(store, FakeRecommendationCache()) }

        val response = client.get("/api/v1/recommendations?type=PERSONALIZED&userId=user-1")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("popular-fallback"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: RecommendationStore, cache: RecommendationCache) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureRecommendationRoutes(store, cache, json) }
    }

    private class FakeRecommendationCache : RecommendationCache {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String, ttl: Duration) { values[key] = value }
    }

    private class FakeRecommendationStore : RecommendationStore {
        var recommendationCalls = 0
        var empty = false
        var failure: Throwable? = null
        override fun record(request: BehaviorRequest): Int? = null
        override fun recommendations(type: RecommendationType, userId: String?, productId: String?, limit: Int): List<String> { recommendationCalls++; failure?.let { throw it }; return if (empty) emptyList() else listOf("product-2", "product-3") }
        override fun popular(limit: Int) = listOf("popular-1", "popular-2").take(limit)
    }
}
