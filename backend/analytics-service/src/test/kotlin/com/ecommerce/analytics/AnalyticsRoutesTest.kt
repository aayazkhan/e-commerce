package com.ecommerce.analytics

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

class AnalyticsRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))

    @Test
    fun `summary and replay require separate permissions and preserve exact inputs`() = testApplication {
        val store = FakeAnalyticsStore()
        application { installRoutes(store) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/analytics/summary").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/analytics/summary") { auth(token()) }.status)
        val summary = client.get("/api/v1/analytics/summary?from=2026-08-20&to=2026-08-21") { auth(token("ADMIN_ANALYTICS_READ")) }
        assertEquals(HttpStatusCode.OK, summary.status)
        assertEquals("2026-08-20", store.summaryFrom)
        assertEquals("2026-08-21", store.summaryTo)
        assertTrue(summary.bodyAsText().contains("conversionRate"))

        val replayBody = json.encodeToString(ReplayRequest("2026-08-20T00:00:00Z", "2026-08-21T00:00:00Z"))
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/analytics/replay") { auth(token("ADMIN_ANALYTICS_READ")); contentType(ContentType.Application.Json); setBody(replayBody) }.status)
        val replay = client.post("/api/v1/admin/analytics/replay") { auth(token("ADMIN_ANALYTICS_REPLAY")); contentType(ContentType.Application.Json); setBody(replayBody) }
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(ReplayRequest("2026-08-20T00:00:00Z", "2026-08-21T00:00:00Z"), store.replayRequest)
        assertTrue(replay.bodyAsText().contains("eventsSeen"))
    }

    @Test
    fun `downstream analytics errors are mapped without changing error code`() = testApplication {
        val store = FakeAnalyticsStore().also { it.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "warehouse unavailable", 503, retryable = true) }
        application { installRoutes(store) }

        val response = client.get("/api/v1/analytics/summary") { auth(token("ADMIN_ANALYTICS_READ")) }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("DEPENDENCY_UNAVAILABLE"))
        assertTrue(response.bodyAsText().contains("warehouse unavailable"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: AnalyticsStore) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureAnalyticsRoutes(store, verifier) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(value: String) = header(HttpHeaders.Authorization, "Bearer $value")

    private fun token(vararg permissions: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"admin-1\",\"roles\":[],\"permissions\":[${permissions.joinToString { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeAnalyticsStore : AnalyticsStore {
        var summaryFrom: String? = null
        var summaryTo: String? = null
        var replayRequest: ReplayRequest? = null
        var failure: ApiException? = null
        override fun summary(from: String?, to: String?): MetricSummary {
            failure?.let { throw it }
            summaryFrom = from
            summaryTo = to
            return MetricSummary(from, to, 8, 3, 2, 1, 2, 1, 2, 1500, .5, 0.0)
        }
        override fun replay(request: ReplayRequest): ReplayResponse {
            replayRequest = request
            return ReplayResponse("replay-1", 8)
        }
    }
}
