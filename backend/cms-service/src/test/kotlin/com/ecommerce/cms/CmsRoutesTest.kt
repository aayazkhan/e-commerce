package com.ecommerce.cms

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
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

class CmsRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))

    @Test
    fun `public page uses cache hit and miss and maps not found`() = testApplication {
        val store = FakeCmsStore()
        val cache = FakeCmsCache()
        application { installRoutes(store, cache) }

        val first = client.get("/api/v1/cms/pages/home")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(1, store.publicCalls)
        assertEquals(1, cache.puts)

        val second = client.get("/api/v1/cms/pages/home")
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, store.publicCalls)

        val missing = client.get("/api/v1/cms/pages/missing")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `admin routes enforce authentication permissions and cache invalidation`() = testApplication {
        val store = FakeCmsStore()
        val cache = FakeCmsCache()
        application { installRoutes(store, cache) }

        val body = json.encodeToString(CmsPageRequest("home", "Home", "{\"body\":\"updated\"}"))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/admin/cms/pages") { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/cms/pages") { auth(token()); contentType(ContentType.Application.Json); setBody(body) }.status)

        val write = token("ADMIN_CMS_WRITE")
        val created = client.post("/api/v1/admin/cms/pages") { auth(write); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("user-1", store.lastActor)
        assertTrue(cache.deleted.contains("cms:page:home"))

        val listed = client.get("/api/v1/admin/cms/pages?limit=7") { auth(token("ADMIN_CMS_READ")) }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(7, store.lastLimit)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/cms/pages") { auth(token()) }.status)

        val fetched = client.get("/api/v1/admin/cms/pages/page-1") { auth(token("ADMIN_CMS_READ")) }
        assertEquals(HttpStatusCode.OK, fetched.status)
        val missing = client.get("/api/v1/admin/cms/pages/missing") { auth(token("ADMIN_CMS_READ")) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `write lifecycle routes call the correct permission and invalidate public cache`() = testApplication {
        val store = FakeCmsStore()
        val cache = FakeCmsCache()
        application { installRoutes(store, cache) }
        val write = token("ADMIN_CMS_WRITE")
        val publish = token("ADMIN_CMS_PUBLISH")
        val pageBody = json.encodeToString(CmsPageRequest("home", "Updated", "{\"body\":\"v2\"}"))

        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/admin/cms/pages/page-1") { auth(write); contentType(ContentType.Application.Json); setBody(pageBody) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/cms/pages/page-1/submit") { auth(write) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/cms/pages/page-1/approve") { auth(publish) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/cms/pages/page-1/publish") { auth(publish); contentType(ContentType.Application.Json); setBody(json.encodeToString(CmsPublishRequest())) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/cms/pages/page-1/unpublish") { auth(publish) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/cms/pages/page-1/rollback") { auth(write); contentType(ContentType.Application.Json); setBody(json.encodeToString(CmsRollbackRequest(1, 1))) }.status)
        assertTrue(cache.deleted.count { it == "cms:page:home" } >= 4)
        assertEquals(listOf("update", "submit", "approve", "publish", "unpublish", "rollback"), store.operations)
    }

    @Test
    fun `repository conflict is exposed as a typed API error`() = testApplication {
        val store = FakeCmsStore().also { it.failure = ApiException(ErrorCode.CONFLICT, "stale page", 409) }
        application { installRoutes(store, FakeCmsCache()) }

        val response = client.patch("/api/v1/admin/cms/pages/page-1") {
            auth(token("ADMIN_CMS_WRITE"))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CmsPageRequest("home", "Updated", "{}")))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("CONFLICT"))
        assertTrue(response.bodyAsText().contains("stale page"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: CmsStore, cache: CmsCache) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureCmsRoutes(store, cache, verifier, json) }
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

    private class FakeCmsCache : CmsCache {
        private val values = mutableMapOf<String, String>()
        var puts = 0
        val deleted = mutableListOf<String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String, ttl: Duration) { values[key] = value; puts++ }
        override fun delete(key: String) { values.remove(key); deleted += key }
    }

    private class FakeCmsStore : CmsStore {
        private val page = CmsPageResponse("page-1", "home", "Home", CmsStatus.DRAFT, 1, 1, "{\"body\":\"v1\"}", SeoMetadata(), "admin-1", "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z")
        var publicCalls = 0
        var lastActor: String? = null
        var lastLimit: Int? = null
        var failure: ApiException? = null
        val operations = mutableListOf<String>()
        override fun create(actor: String, request: CmsPageRequest, correlation: String): CmsPageResponse { failure?.let { throw it }; operations += "create"; lastActor = actor; return page.copy(slug = request.slug, title = request.title, contentJson = request.contentJson) }
        override fun list(limit: Int): List<CmsPageResponse> { lastLimit = limit; return listOf(page) }
        override fun get(id: String): CmsPageResponse? = page.takeIf { id == it.id }
        override fun public(slug: String): CmsPageResponse? { publicCalls++; return page.takeIf { slug == it.slug } }
        override fun update(id: String, actor: String, request: CmsPageRequest, correlation: String): CmsPageResponse { failure?.let { throw it }; operations += "update"; return page.copy(title = request.title, contentJson = request.contentJson) }
        override fun submit(id: String, actor: String, correlation: String): CmsPageResponse { operations += "submit"; return page.copy(status = CmsStatus.IN_REVIEW) }
        override fun approve(id: String, actor: String, correlation: String): CmsPageResponse { operations += "approve"; return page.copy(status = CmsStatus.APPROVED) }
        override fun publish(id: String, actor: String, request: CmsPublishRequest, correlation: String): CmsPageResponse { operations += "publish"; return page.copy(status = CmsStatus.PUBLISHED) }
        override fun unpublish(id: String, actor: String, correlation: String): CmsPageResponse { operations += "unpublish"; return page.copy(status = CmsStatus.UNPUBLISHED) }
        override fun rollback(id: String, actor: String, request: CmsRollbackRequest, correlation: String): CmsPageResponse { operations += "rollback"; return page.copy(status = CmsStatus.DRAFT) }
    }
}
