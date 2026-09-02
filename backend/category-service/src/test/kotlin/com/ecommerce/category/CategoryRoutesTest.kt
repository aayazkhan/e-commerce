package com.ecommerce.category

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
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
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))
    private val category = Category("category-1", null, "Footwear", "footwear", "Shoes", null, null, 1, CategoryStatus.ACTIVE, null, null, emptyList(), null, 1, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")

    @Test
    fun `public routes use cache, list children and map not found`() = testApplication {
        val store = FakeCategoryStore()
        val cache = FakeCategoryCache()
        application { installRoutes(store, cache) }

        val first = client.get("/api/v1/categories")
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.bodyAsText().contains("footwear"))
        assertEquals(1, store.listCalls)
        val second = client.get("/api/v1/categories")
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, store.listCalls)

        val tree = client.get("/api/v1/categories/tree")
        assertEquals(HttpStatusCode.OK, tree.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/categories/tree").status)
        val children = client.get("/api/v1/categories/category-1/children")
        assertEquals(HttpStatusCode.OK, children.status)
        val missing = client.get("/api/v1/categories/missing")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `category cache decoding failures fall back to storage and preserve parent filters`() = testApplication {
        val store = FakeCategoryStore()
        val cache = FakeCategoryCache()
        cache.seed("category:list:root", "not-json")
        cache.seed("category:tree", "not-json")
        application { installRoutes(store, cache) }

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/categories").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/categories/tree").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/categories?parentId=parent-1").status)
        assertEquals(2, store.listCalls)
    }

    @Test
    fun `admin routes enforce authentication authorization and lifecycle operations`() = testApplication {
        val store = FakeCategoryStore()
        val cache = FakeCategoryCache()
        application { installRoutes(store, cache) }
        val admin = token(roles = listOf("ADMIN"), permissions = emptyList())
        val superAdmin = token(roles = listOf("SUPER_ADMIN"), permissions = emptyList())
        val editor = token(roles = emptyList(), permissions = listOf("CATEGORY_CREATE", "CATEGORY_UPDATE", "CATEGORY_DELETE"))
        assertEquals("admin-1", verifier.verify(admin).subject)

        val missing = client.post("/api/v1/admin/categories") { contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        val forbidden = client.post("/api/v1/admin/categories") { header(HttpHeaders.Authorization, "Bearer ${token()}"); contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val created = client.post("/api/v1/admin/categories") { header(HttpHeaders.Authorization, "Bearer $editor"); contentType(ContentType.Application.Json); setBody(requestBody()) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertTrue(cache.deleted.contains("category:tree"))
        val updated = client.patch("/api/v1/admin/categories/category-1") { header(HttpHeaders.Authorization, "Bearer $editor"); contentType(ContentType.Application.Json); setBody(requestBody(status = "ACTIVE")) }
        assertEquals(HttpStatusCode.OK, updated.status)
        val published = client.post("/api/v1/admin/categories/category-1/publish") { header(HttpHeaders.Authorization, "Bearer $admin") }
        assertEquals(HttpStatusCode.OK, published.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/categories/category-1/publish") { header(HttpHeaders.Authorization, "Bearer $superAdmin") }.status)
        val unpublished = client.post("/api/v1/admin/categories/category-1/unpublish") { header(HttpHeaders.Authorization, "Bearer $editor") }
        assertEquals(HttpStatusCode.OK, unpublished.status)
        val reordered = client.patch("/api/v1/admin/categories/reorder") { header(HttpHeaders.Authorization, "Bearer $editor"); contentType(ContentType.Application.Json); setBody("{\"categoryIds\":[\"category-1\"]}") }
        assertEquals(HttpStatusCode.OK, reordered.status)
        val deleted = client.delete("/api/v1/admin/categories/category-1") { header(HttpHeaders.Authorization, "Bearer $editor") }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(deleted.bodyAsText().contains("deleted"))
    }

    @Test
    fun `category request maps every lifecycle field and invalid status is rejected`() {
        val request = CategoryRequest("parent-1", " Shoes ", "shoes", "description", "image", "icon", 3, "active", "SEO", "description", listOf("shoes"), "https://canonical.test")
        val input = request.input()
        assertEquals("parent-1", input.parentId)
        assertEquals(CategoryStatus.ACTIVE, input.status)
        assertEquals(listOf("shoes"), input.seoKeywords)
        val sparse = json.decodeFromString<CategoryRequest>("{\"name\":\"Shoes\",\"slug\":\"shoes\"}")
        assertEquals(0, sparse.sortOrder)
        assertEquals(emptyList(), sparse.seoKeywords)
        kotlin.test.assertFailsWith<IllegalArgumentException> { request.copy(status = "unknown").input() }
        assertEquals(request, json.decodeFromString<CategoryRequest>(json.encodeToString(request)))
    }

    @Test
    fun `category cache hit and every lifecycle status are mapped exactly`() = testApplication {
        val store = FakeCategoryStore()
        val cache = FakeCategoryCache()
        cache.seed("category:list:root", json.encodeToString(listOf(sampleCategory)))
        application { installRoutes(store, cache) }

        val cached = client.get("/api/v1/categories")
        assertEquals(HttpStatusCode.OK, cached.status)
        assertEquals(0, store.listCalls)

        CategoryStatus.entries.forEach { status ->
            val input = CategoryRequest(name = "Shoes", slug = "shoes", status = status.name).input()
            assertEquals(status, input.status)
        }
    }

    private fun io.ktor.server.application.Application.installRoutes(store: CategoryStore, cache: CategoryCache) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(com.ecommerce.platform.error.ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureCategoryRoutes(store, cache, verifier) }
    }

    private fun requestBody(status: String = "DRAFT") = "{\"name\":\"Shoes\",\"slug\":\"shoes\",\"status\":\"$status\",\"seoKeywords\":[\"shoes\"]}"

    private fun token(roles: List<String> = emptyList(), permissions: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"admin-1\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeCategoryStore : CategoryStore {
        var listCalls = 0
        override fun list(parentId: String?, publicOnly: Boolean): List<Category> { listCalls++; return listOf(sampleCategory) }
        override fun find(id: String, publicOnly: Boolean): Category? = sampleCategory.takeIf { id == it.id }
        override fun tree() = listOf(sampleCategory)
        override fun create(input: CategoryInput, correlationId: String) = sampleCategory
        override fun update(id: String, input: CategoryInput, correlationId: String) = sampleCategory
        override fun delete(id: String, correlationId: String) = 1
        override fun changeStatus(id: String, status: CategoryStatus, correlationId: String) = sampleCategory.copy(status = status)
        override fun reorder(parentId: String?, ids: List<String>, correlationId: String) = listOf(sampleCategory)
    }

    private class FakeCategoryCache : CategoryCache {
        private val values = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        fun seed(key: String, value: String) { values[key] = value }
        override fun ping() = true
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String, ttl: Duration) { values[key] = value }
        override fun delete(vararg keys: String) { deleted += keys }
    }

    private companion object {
        val sampleCategory = Category("category-1", null, "Footwear", "footwear", "Shoes", null, null, 1, CategoryStatus.ACTIVE, null, null, emptyList(), null, 1, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
    }
}
