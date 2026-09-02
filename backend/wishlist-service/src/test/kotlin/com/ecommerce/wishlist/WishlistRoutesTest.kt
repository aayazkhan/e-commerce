package com.ecommerce.wishlist

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
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WishlistRoutesTest {
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `wishlist routes cover authentication reads add aliases removes and clear`() = testApplication {
        val store = FakeWishlistStore()
        val enricher = FakeEnricher()
        application { installRoutes(store, enricher) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/wishlist").status)
        val auth = token()

        val page = client.get("/api/v1/wishlist?cursor=c1&limit=bad&currency=usd") { bearer(auth) }
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("user-1", store.lastUser)
        assertEquals(20, store.lastLimit)
        assertTrue(page.bodyAsText().contains("USD"))

        val add = client.post("/api/v1/wishlist?currency=gbp") {
            bearer(auth); contentType(ContentType.Application.Json); setBody("{\"productId\":\"p1\",\"variantId\":\"v1\"}")
        }
        assertEquals(HttpStatusCode.Created, add.status)
        assertEquals("GBP", enricher.lastCurrency)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/wishlist/items") {
            bearer(auth); contentType(ContentType.Application.Json); setBody("{\"productId\":\"p2\",\"variantId\":\"v2\"}")
        }.status)
        assertEquals(2, store.adds)

        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/wishlist/v1") { bearer(auth) }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/wishlist/items/v2") { bearer(auth) }.status)
        assertEquals("v2", store.lastVariant)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/wishlist") { bearer(auth) }.status)
        assertEquals(1, store.clears)
    }

    @Test
    fun `wishlist invalid token and missing path are mapped safely`() = testApplication {
        application { installRoutes(FakeWishlistStore(), FakeEnricher()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/wishlist") { bearer("bad") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/wishlist/items/").status)
    }

    private fun io.ktor.server.application.Application.installRoutes(store: WishlistRouteStore, enricher: WishlistRouteEnricher) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureWishlistRoutes(store, enricher, verifier) }
    }

    private fun HttpRequestBuilder.bearer(value: String) { header(HttpHeaders.Authorization, "Bearer $value") }

    private fun token(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"user-1\",\"roles\":[],\"permissions\":[],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256")) }
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeWishlistStore : WishlistRouteStore {
        var lastUser: String? = null
        var lastLimit: Int? = null
        var lastVariant: String? = null
        var adds = 0
        var clears = 0
        override fun add(userId: String, productId: String, variantId: String, correlationId: String): WishlistRecord { adds++; lastUser = userId; return record(productId, variantId) }
        override fun remove(userId: String, variantId: String, correlationId: String): Boolean { lastUser = userId; lastVariant = variantId; return variantId == "v1" }
        override fun clear(userId: String, correlationId: String): Int { clears++; lastUser = userId; return 2 }
        override fun list(userId: String, cursor: String?, limit: Int): WishlistList { lastUser = userId; lastLimit = limit; return WishlistList(listOf(record("p1", "v1")), "next", true) }
        private fun record(productId: String, variantId: String) = WishlistRecord("item-$variantId", "user-1", productId, variantId, "2026-08-20T00:00:00Z")
    }

    private class FakeEnricher : WishlistRouteEnricher {
        var lastCurrency: String? = null
        override fun enrich(record: WishlistRecord, currency: String): WishlistItemView { lastCurrency = currency; return WishlistItemView(record.id, record.productId, record.variantId, record.createdAt, "IN_STOCK", 3, 100, currency) }
    }
}
