package com.ecommerce.search

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRoutesTest {
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `search query routes normalize parameters and return search data`() = testApplication {
        val store = FakeSearchStore()
        application { installRoutes(store, FakeCatalogClient()) }

        val result = client.get("/api/v1/search?q=phone&categoryId=c1&brandId=b1&minPrice=100&maxPrice=2000&sort=price_asc&from=-4&limit=999")
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(SearchQuery("phone", "c1", "b1", 100, 2000, "price_asc", 0, 100), store.lastQuery)
        assertTrue(result.bodyAsText().contains("p1"))
        client.get("/api/v1/search?limit=invalid")
        assertEquals(24, store.lastQuery!!.size)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/search/suggestions?q=phone").status)
        assertEquals("phone", store.lastSuggestion)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/search/suggestions").status)
        assertEquals("", store.lastSuggestion)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/search/filters").status)
        assertEquals(1, store.filterCalls)

        client.get("/api/v1/search")
        assertEquals(SearchQuery("", null, null, null, null, null, 0, 24), store.lastQuery)
        client.get("/api/v1/search?limit=0&from=invalid&minPrice=invalid&maxPrice=invalid")
        assertEquals(SearchQuery("", null, null, null, null, null, 0, 1), store.lastQuery)
        client.get("/api/v1/search?limit=101&from=7&minPrice=50&maxPrice=900")
        assertEquals(SearchQuery("", null, null, 50, 900, null, 7, 100), store.lastQuery)
    }

    @Test
    fun `search reindex routes cover authorization pagination item writes and product outcomes`() = testApplication {
        val store = FakeSearchStore()
        val catalog = FakeCatalogClient(
            pages = listOf(
                CatalogSearchResponse(200, "{\"items\":[{\"id\":\"p1\",\"name\":\"One\"}],\"nextCursor\":\"next\",\"hasMore\":true}"),
                CatalogSearchResponse(200, "{\"items\":[{\"id\":\"p2\",\"name\":\"Two\"}],\"nextCursor\":null,\"hasMore\":false}")
            )
        )
        application { installRoutes(store, catalog) }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/admin/search/reindex").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/search/reindex") { auth(token()) }.status)
        val admin = token(roles = listOf("ADMIN"))
        val reindex = client.post("/api/v1/admin/search/reindex") { auth(admin) }
        assertEquals(HttpStatusCode.OK, reindex.status)
        assertEquals("products-reindex", store.target)
        assertEquals(listOf("p1", "p2"), store.indexed.map { it.first })
        assertEquals(listOf("/api/v1/products?limit=100", "/api/v1/products?limit=100&cursor=next"), catalog.paths)
        assertEquals("products-reindex", store.swapped)

        catalog.productResponse = CatalogSearchResponse(404, "")
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/search/reindex/missing") { auth(admin) }.status)
        assertEquals("missing", store.deleted)
        catalog.productResponse = CatalogSearchResponse(200, "{\"id\":\"p3\",\"name\":\"Three\"}")
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/search/reindex/p3") { auth(admin) }.status)
        assertEquals("p3", store.productIndexed?.first)
        catalog.productResponse = CatalogSearchResponse(503, "down")
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/v1/admin/search/reindex/p4") { auth(admin) }.status)
        catalog.productResponse = CatalogSearchResponse(199, "early")
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/v1/admin/search/reindex/p5") { auth(admin) }.status)

        catalog.pages = listOf(CatalogSearchResponse(299, "{\"items\":[],\"nextCursor\":null,\"hasMore\":false}"))
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/search/reindex") { auth(token(roles = listOf("SUPER_ADMIN"))) }.status)
        catalog.pages = listOf(CatalogSearchResponse(199, "early"))
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/v1/admin/search/reindex") { auth(admin) }.status)
    }

    @Test
    fun `search reindex maps catalog failures and malformed pages`() = testApplication {
        val store = FakeSearchStore()
        val catalog = FakeCatalogClient(pages = listOf(CatalogSearchResponse(503, "down")))
        application { installRoutes(store, catalog) }
        val admin = token(permissions = listOf("SEARCH_REINDEX"))
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/v1/admin/search/reindex") { auth(admin) }.status)
        catalog.pages = listOf(CatalogSearchResponse(200, "{\"items\":[{\"name\":\"missing id\"}],\"nextCursor\":null,\"hasMore\":false}"))
        assertEquals(HttpStatusCode.InternalServerError, client.post("/api/v1/admin/search/reindex") { auth(admin) }.status)
        catalog.pages = listOf(CatalogSearchResponse(200, "{\"items\":[],\"nextCursor\":null,\"hasMore\":false}"))
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/search/reindex") { auth(token(permissions = listOf("SEARCH_REINDEX"))) }.status)
    }

    private fun io.ktor.server.application.Application.installRoutes(store: SearchRouteStore, catalog: CatalogSearchClient) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureSearchRoutes(store, verifier, SearchRouteConfig("catalog"), catalog) }
    }

    private fun HttpRequestBuilder.auth(value: String) { header(HttpHeaders.Authorization, "Bearer $value") }

    private fun token(roles: List<String> = emptyList(), permissions: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"admin-1\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256")) }
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private data class SearchQuery(val query: String, val category: String?, val brand: String?, val min: Long?, val max: Long?, val sort: String?, val from: Int, val size: Int)

    private class FakeSearchStore : SearchRouteStore {
        var lastQuery: SearchQuery? = null
        var lastSuggestion: String? = null
        var filterCalls = 0
        var target: String? = null
        var swapped: String? = null
        var deleted: String? = null
        var productIndexed: Pair<String, JsonObject>? = null
        val indexed = mutableListOf<Pair<String, JsonObject>>()
        override fun search(query: String, categoryId: String?, brandId: String?, minPrice: Long?, maxPrice: Long?, sort: String?, from: Int, size: Int): SearchResponse { lastQuery = SearchQuery(query, categoryId, brandId, minPrice, maxPrice, sort, from, size); return SearchResponse(listOf(SearchHit("p1", 1.0, buildJsonObject { put("name", "Phone") })), 1, 3) }
        override fun suggestions(query: String) = SuggestionResponse(listOf(query).filter { it.isNotBlank() }.ifEmpty { listOf("fallback") }.also { lastSuggestion = query })
        override fun filters(): FilterResponse { filterCalls++; return FilterResponse(emptyMap()) }
        override fun newReindexIndex(): String { target = "products-reindex"; return target!! }
        override fun indexInto(index: String, id: String, document: JsonObject) { indexed += id to document }
        override fun swapAlias(index: String) { swapped = index }
        override fun deleteProduct(id: String) { deleted = id }
        override fun indexProduct(id: String, document: JsonObject) { productIndexed = id to document }
    }

    private class FakeCatalogClient(var pages: List<CatalogSearchResponse> = emptyList(), var productResponse: CatalogSearchResponse = CatalogSearchResponse(200, "{}")) : CatalogSearchClient {
        val paths = mutableListOf<String>()
        override fun get(path: String): CatalogSearchResponse { paths += path; return if (path.contains("?limit=")) pages.first().also { pages = pages.drop(1) } else productResponse }
    }
}
