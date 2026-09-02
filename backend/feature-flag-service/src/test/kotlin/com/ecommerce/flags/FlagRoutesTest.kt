package com.ecommerce.flags

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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

class FlagRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))
    private val flag = FeatureFlagResponse("checkout.v2", FlagValueType.BOOLEAN, "true", "false", true, "production", 10_000, "{}", 1, "admin-1", "2026-08-21T00:00:00Z")

    @Test
    fun `evaluation cache supports miss hit malformed value and safe fallback`() = testApplication {
        val store = FakeFlagStore()
        val cache = FakeFlagCache()
        application { installRoutes(store, cache) }
        val request = "{\"key\":\"checkout.v2\",\"userId\":\"user-1\",\"fallbackValue\":\"fallback\"}"
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/feature-flags/evaluate") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/feature-flags/evaluate") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(1, store.evaluateCalls)
        cache.value = "not-json"
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/feature-flags/evaluate") { contentType(ContentType.Application.Json); setBody(request) }.status)
        store.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "flags unavailable", 503)
        cache.value = null
        val fallback = client.post("/api/v1/feature-flags/evaluate") { contentType(ContentType.Application.Json); setBody("{\"key\":\"new\",\"fallbackValue\":\"safe\"}") }
        assertEquals(HttpStatusCode.OK, fallback.status)
        assertTrue(fallback.bodyAsText().contains("safe-default"))
    }

    @Test
    fun `admin flag routes enforce permissions and immutable key validation`() = testApplication {
        val store = FakeFlagStore()
        application { installRoutes(store, FakeFlagCache()) }
        val read = token(listOf("ADMIN_FEATURE_FLAG_READ"))
        val update = token(listOf("ADMIN_FEATURE_FLAG_UPDATE"))
        val body = json.encodeToString(FeatureFlagRequest("checkout.v2", FlagValueType.BOOLEAN, "true", "false"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/feature-flags").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/feature-flags") { auth(read); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/feature-flags") { auth(read) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/admin/feature-flags") { auth(update); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/feature-flags/checkout.v2") { auth(read) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/admin/feature-flags/missing") { auth(read) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.patch("/api/v1/admin/feature-flags/other") { auth(update); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/admin/feature-flags/checkout.v2") { auth(update); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/admin/feature-flags/checkout.v2") { auth(update) }.status)
    }

    private fun io.ktor.server.application.Application.installRoutes(store: FlagStore, cache: FlagCache) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureFlagRoutes(store, cache, verifier, json) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(value: String) = header(HttpHeaders.Authorization, "Bearer $value")

    private fun token(permissions: List<String>): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"admin-1\",\"roles\":[],\"permissions\":[${permissions.joinToString { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeFlagCache : FlagCache {
        var value: String? = null
        override fun get(key: String) = value
        override fun put(key: String, value: String, ttl: Duration) { this.value = value }
    }

    private inner class FakeFlagStore : FlagStore {
        var evaluateCalls = 0
        var failure: ApiException? = null
        override fun list() = listOf(flag)
        override fun get(key: String) = flag.takeIf { it.key == key }
        override fun save(actor: String, request: FeatureFlagRequest, correlation: String) = flag.copy(key = request.key, valueType = request.valueType, version = flag.version + 1, updatedBy = actor)
        override fun delete(actor: String, key: String, correlation: String) = Unit
        override fun evaluate(request: EvaluationRequest): EvaluationResponse { failure?.let { throw it }; evaluateCalls++; return EvaluationResponse(request.key, "true", true, 1, "rollout") }
    }
}
