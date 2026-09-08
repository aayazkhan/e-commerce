package com.ecommerce.catalog

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))

    @Test
    fun `public product routes cache results and expose not found`() = testApplication {
        val store = FakeCatalogStore()
        val cache = FakeCatalogCache()
        application { installRoutes(store, cache) }

        val first = client.get("/api/v1/products?limit=2&categoryId=cat-1&sellerId=seller-1")
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.bodyAsText().contains("running-shoe"))
        assertEquals(1, store.listCalls)
        assertEquals(2, store.lastLimit)
        assertEquals("cat-1", store.lastCategoryId)
        assertEquals("seller-1", store.lastSellerId)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products?limit=2&categoryId=cat-1&sellerId=seller-1").status)
        assertEquals(1, store.listCalls)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/slug/running-shoe").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/slug/running-shoe").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/prd-1").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/prd-1").status)
        val missing = client.get("/api/v1/products/slug/missing")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `cache decode failures and empty pages fall back to the repository`() = testApplication {
        val store = FakeCatalogStore()
        val cache = FakeCatalogCache()
        cache.seed("catalog:product:prd-1", "not-json")
        cache.seed("catalog:slug:running-shoe", "not-json")
        cache.seed("catalog:list::::ACTIVE:24", "{\"items\":[],\"nextCursor\":\"next\",\"hasMore\":true}")
        application { installRoutes(store, cache) }

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/prd-1").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/slug/running-shoe").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products").status)
        assertEquals(1, store.listCalls)
    }

    @Test
    fun `cache accepts a valid empty final page without querying the repository`() = testApplication {
        val store = FakeCatalogStore()
        val cache = FakeCatalogCache()
        cache.seed("catalog:list::::ACTIVE:24", "{\"items\":[],\"nextCursor\":null,\"hasMore\":false}")
        application { installRoutes(store, cache) }

        val response = client.get("/api/v1/products")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("catalog:list::::ACTIVE:24", cache.lastKey)
        assertEquals(0, store.listCalls)
    }

    @Test
    fun `product listing only widens beyond ACTIVE for the matching seller or an admin`() = testApplication {
        val store = FakeCatalogStore()
        val cache = FakeCatalogCache()
        application { installRoutes(store, cache) }

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products?sellerId=seller-1").status)
        assertEquals(true, store.lastRestrictToActive)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products?sellerId=seller-1") { auth(token(subject = "someone-else")) }.status)
        assertEquals(true, store.lastRestrictToActive)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products?sellerId=seller-1") { auth(token(subject = "seller-1")) }.status)
        assertEquals(false, store.lastRestrictToActive)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products?sellerId=seller-1") { auth(token(roles = listOf("ADMIN"))) }.status)
        assertEquals(false, store.lastRestrictToActive)
    }

    @Test
    fun `a single non-ACTIVE product is only visible to its owner or an admin`() = testApplication {
        val store = FakeCatalogStore()
        store.findResult = sampleProduct.copy(status = ProductStatus.DRAFT)
        val cache = FakeCatalogCache()
        application { installRoutes(store, cache) }

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/products/prd-1").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/products/prd-1") { auth(token(subject = "someone-else")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/prd-1") { auth(token(subject = "seller-1")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products/prd-1") { auth(token(roles = listOf("ADMIN"))) }.status)
    }

    @Test
    fun `write routes enforce permissions and execute lifecycle plus cache invalidation`() = testApplication {
        val store = FakeCatalogStore()
        val cache = FakeCatalogCache()
        application { installRoutes(store, cache) }
        val editor = token(permissions = listOf("PRODUCT_CREATE", "PRODUCT_UPDATE", "PRODUCT_DELETE", "PRODUCT_PUBLISH"))
        val ordinary = token()

        val missing = client.post("/api/v1/products") { contentType(ContentType.Application.Json); setBody(body()) }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        val forbidden = client.post("/api/v1/products") { header(HttpHeaders.Authorization, "Bearer $ordinary"); contentType(ContentType.Application.Json); setBody(body()) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val created = client.post("/api/v1/products") { auth(editor); contentType(ContentType.Application.Json); setBody(body()) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("admin-1", store.createdBy)
        assertEquals("admin-1", store.createdInput?.sellerId)
        assertTrue(cache.deleted.contains("catalog:slug:new-shoe"))

        val updated = client.patch("/api/v1/products/prd-1") { auth(editor); contentType(ContentType.Application.Json); setBody(body(ownerType = "ADMIN", sellerId = "admin-catalog", slug = "updated-shoe")) }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("admin-catalog", store.updatedInput?.sellerId)
        assertTrue(cache.deleted.containsAll(listOf("catalog:product:prd-1", "catalog:slug:running-shoe", "catalog:slug:updated-shoe")))
        assertEquals(HttpStatusCode.NotFound, client.patch("/api/v1/products/missing") { auth(editor); contentType(ContentType.Application.Json); setBody(body()) }.status)

        val deleted = client.delete("/api/v1/products/prd-1") { auth(editor) }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(deleted.bodyAsText().contains("archived"))
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/products/prd-1/publish") { auth(editor) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/products/prd-1/unpublish") { auth(editor) }.status)
    }

    @Test
    fun `role based permissions allow seller and administrative principals and normalize limits`() = testApplication {
        val store = FakeCatalogStore()
        application { installRoutes(store, FakeCatalogCache()) }
        val seller = token(roles = listOf("SELLER"))
        val admin = token(roles = listOf("ADMIN"))

        assertEquals(HttpStatusCode.Created, client.post("/api/v1/products") { auth(seller); contentType(ContentType.Application.Json); setBody(body()) }.status)
        assertEquals("admin-1", store.createdInput?.sellerId)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/products") { auth(admin); contentType(ContentType.Application.Json); setBody(body(ownerType = "ADMIN")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products?limit=invalid").status)
        assertEquals(24, store.lastLimit)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products?limit=0").status)
        assertEquals(1, store.lastLimit)
    }

    @Test
    fun `super admin role satisfies protected routes without explicit permission`() = testApplication {
        val store = FakeCatalogStore()
        application { installRoutes(store, FakeCatalogCache()) }

        val response = client.post("/api/v1/products") {
            auth(token(roles = listOf("SUPER_ADMIN")))
            contentType(ContentType.Application.Json)
            setBody(body())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("admin-1", store.createdBy)
    }

    @Test
    fun `internal publish requires exact service token and preserves actor`() = testApplication {
        val store = FakeCatalogStore()
        application { installRoutes(store, FakeCatalogCache()) }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/internal/products/prd-1/publish") { header("X-Internal-Service-Token", "wrong") }.status)
        val response = client.post("/api/v1/internal/products/prd-1/publish") { header("X-Internal-Service-Token", "internal-secret"); header("X-Actor-Id", "order-service") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("order-service", store.statusActor)
        assertEquals(ProductStatus.ACTIVE, store.status)
    }

    @Test
    fun `blank internal token configuration is rejected before header comparison`() = testApplication {
        val store = FakeCatalogStore()
        application { installRoutes(store, FakeCatalogCache(), internalToken = "") }

        val response = client.post("/api/v1/internal/products/prd-1/publish")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ProductStatus.DRAFT, store.status)
    }

    @Test
    fun `remaining catalog branches cover cache acceptance alternate owners and missing resources`() = testApplication {
        val store = FakeCatalogStore()
        val cache = FakeCatalogCache()
        application { installRoutes(store, cache) }

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/products/missing").status)

        val editor = token(permissions = listOf("PRODUCT_CREATE", "PRODUCT_UPDATE", "PRODUCT_DELETE", "PRODUCT_PUBLISH"))
        val systemProduct = client.post("/api/v1/products") {
            auth(editor)
            contentType(ContentType.Application.Json)
            setBody(body(ownerType = "SYSTEM", sellerId = "system-owner"))
        }
        assertEquals(HttpStatusCode.Created, systemProduct.status)
        assertEquals(OwnerType.SYSTEM, store.createdInput?.ownerType)
        assertEquals("system-owner", store.createdInput?.sellerId)

        val sellerUpdate = client.patch("/api/v1/products/prd-1") {
            auth(editor)
            contentType(ContentType.Application.Json)
            setBody(body(ownerType = "SELLER", sellerId = null, slug = "seller-update"))
        }
        assertEquals(HttpStatusCode.OK, sellerUpdate.status)
        assertEquals("admin-1", store.updatedInput?.sellerId)

        val deleteMissing = client.delete("/api/v1/products/missing") { auth(editor) }
        assertEquals(HttpStatusCode.OK, deleteMissing.status)
        assertTrue(cache.deleted.contains("catalog:slug:null"))

        val defaultActor = client.post("/api/v1/internal/products/prd-1/publish") {
            header("X-Internal-Service-Token", "internal-secret")
        }
        assertEquals(HttpStatusCode.OK, defaultActor.status)
        assertEquals("admin-service", store.statusActor)
    }

    @Test
    fun `product request mapping covers every supported owner product and variant status`() {
        ProductStatus.entries.forEach { status ->
            val input = ProductRequest(
                ownerType = "SELLER",
                categoryId = "cat-1",
                name = "Shoe",
                slug = "shoe",
                description = "description",
                status = status.name,
                variants = VariantStatus.entries.map { variantStatus -> VariantRequest("sku-${variantStatus.name}", status = variantStatus.name) },
            ).input("seller-1")
            assertEquals(status, input.status)
            assertEquals(VariantStatus.entries.toSet(), input.variants.map { it.status }.toSet())
        }
        OwnerType.entries.forEach { owner ->
            val input = ProductRequest(ownerType = owner.name, categoryId = "cat-1", name = "Shoe", slug = "shoe", description = "description").input("owner-1")
            assertEquals(owner, input.ownerType)
        }
    }

    @Test
    fun `product request maps nested variants media and rejects unknown enums`() {
        val request = ProductRequest(
            sellerId = "seller-2", ownerType = "system", brandId = "brand-1", categoryId = "cat-1", name = " Shoe ", slug = "shoe",
            description = "description", shortDescription = "short", skuReference = "ref", status = "review", taxCategory = "standard",
            attributes = mapOf("color" to "red"), seoTitle = "SEO", seoDescription = "SEO desc", canonicalUrl = "https://shop.test/shoe",
            variants = listOf(VariantRequest("sku-1", "barcode", mapOf("size" to "9"), "10.00", 500, mapOf("h" to "2"), "inactive")),
            media = listOf(MediaRequest("media-1", "VIDEO", "https://cdn.test/video", 2, "demo"))
        )
        val input = request.input("system-owner")
        assertEquals("system-owner", input.sellerId)
        assertEquals(OwnerType.SYSTEM, input.ownerType)
        assertEquals(ProductStatus.REVIEW, input.status)
        assertEquals(VariantStatus.INACTIVE, input.variants.single().status)
        assertEquals("VIDEO", input.media.single().mediaType)
        assertEquals(request, json.decodeFromString<ProductRequest>(json.encodeToString(request)))
        val sparse = json.decodeFromString<ProductRequest>("{\"categoryId\":\"cat-1\",\"name\":\"Shoe\",\"slug\":\"shoe\",\"description\":\"description\"}")
        assertEquals("SELLER", sparse.ownerType)
        assertEquals(emptyList(), sparse.variants)
        assertEquals(VariantRequest("sku-1"), json.decodeFromString<VariantRequest>("{\"sku\":\"sku-1\"}"))
        assertEquals(MediaRequest("media-1"), json.decodeFromString<MediaRequest>("{\"mediaId\":\"media-1\"}"))
        assertFailsWith<IllegalArgumentException> { request.copy(status = "unknown").input("seller") }
        assertFailsWith<IllegalArgumentException> { request.copy(ownerType = "unknown").input("seller") }
        assertFailsWith<IllegalArgumentException> { request.copy(variants = listOf(VariantRequest("sku", status = "unknown"))).input("seller") }
    }

    private fun io.ktor.server.application.Application.installRoutes(store: CatalogStore, cache: CatalogCache, internalToken: String = "internal-secret") {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureCatalogRoutes(store, cache, verifier, internalToken) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun body(ownerType: String = "SELLER", sellerId: String? = null, slug: String = "new-shoe") = json.encodeToString(ProductRequest(sellerId = sellerId, ownerType = ownerType, categoryId = "cat-1", name = "Shoe", slug = slug, description = "A shoe"))

    private fun token(roles: List<String> = emptyList(), permissions: List<String> = emptyList(), subject: String = "admin-1"): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"$subject\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeCatalogStore : CatalogStore {
        var listCalls = 0
        var lastLimit = 0
        var lastCategoryId: String? = null
        var lastSellerId: String? = null
        var lastRestrictToActive: Boolean? = null
        var createdBy: String? = null
        var createdInput: ProductInput? = null
        var updatedInput: ProductInput? = null
        var statusActor: String? = null
        var status = ProductStatus.DRAFT
        var findResult: Product? = sampleProduct
        override fun list(cursor: String?, limit: Int, categoryId: String?, sellerId: String?, status: ProductStatus?, restrictToActive: Boolean): Pair<List<Product>, String?> { listCalls++; lastLimit = limit; lastCategoryId = categoryId; lastSellerId = sellerId; lastRestrictToActive = restrictToActive; return listOf(sampleProduct) to null }
        override fun find(id: String, publicOnly: Boolean): Product? = findResult?.takeIf { it.id == id }
        override fun findBySlug(slug: String): Product? = sampleProduct.takeIf { it.slug == slug }
        override fun create(input: ProductInput, actorId: String, correlationId: String): Product { createdBy = actorId; createdInput = input; return sampleProduct.copy(slug = input.slug, sellerId = input.sellerId) }
        override fun update(id: String, input: ProductInput, actorId: String, correlationId: String): Product { updatedInput = input; return sampleProduct.copy(slug = input.slug, sellerId = input.sellerId) }
        override fun changeStatus(id: String, status: ProductStatus, actorId: String, correlationId: String): Product { this.status = status; statusActor = actorId; return sampleProduct.copy(status = status) }
        override fun delete(id: String, actorId: String, correlationId: String): Int = 1
    }

    private class FakeCatalogCache : CatalogCache {
        private val values = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var lastKey: String? = null
        fun seed(key: String, value: String) { values[key] = value }
        override fun get(key: String): String? { lastKey = key; return values[key] }
        override fun put(key: String, value: String, ttl: Duration) { values[key] = value }
        override fun delete(vararg keys: String) { deleted += keys }
    }

    private companion object {
        val sampleProduct = Product("prd-1", "seller-1", OwnerType.SELLER, null, "cat-1", "Running Shoe", "running-shoe", "A shoe", null, null, ProductStatus.ACTIVE, null, emptyMap(), null, null, null, 1, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
    }
}
