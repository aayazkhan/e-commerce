package com.ecommerce.pricing

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
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricingRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))

    @Test
    fun `current price route uses query dimensions cache and not found mapping`() = testApplication {
        val store = FakePricingStore()
        val cache = FakePricingCache()
        application { installRoutes(store, cache) }

        val path = "/api/v1/pricing/products/product-1?currency=USD&country=US&variantId=variant-1&segment=VIP"
        assertEquals(HttpStatusCode.OK, client.get(path).status)
        assertEquals(1, store.currentCalls)
        assertEquals("USD", store.currency)
        assertEquals("US", store.region)
        assertEquals("VIP", store.segment)
        assertEquals(HttpStatusCode.OK, client.get(path).status)
        assertEquals(1, store.currentCalls)
        val missing = client.get("/api/v1/pricing/products/missing")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `malformed cached price falls back to current price lookup`() = testApplication {
        val store = FakePricingStore()
        val cache = FakePricingCache()
        cache.seed("pricing:product-1::INR:IN:DEFAULT", "not-json")
        application { installRoutes(store, cache) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/pricing/products/product-1").status)
        assertEquals(1, store.currentCalls)
    }

    @Test
    fun `quote route maps items and admin routes enforce permissions and invalidate cache`() = testApplication {
        val store = FakePricingStore()
        val cache = FakePricingCache()
        application { installRoutes(store, cache) }
        val editor = token(permissions = listOf("PRICE_CREATE", "PRICE_UPDATE", "PRICE_DELETE"))
        val ordinary = token()
        val superAdmin = token(roles = listOf("SUPER_ADMIN"))

        val quote = client.post("/api/v1/pricing/quote") { contentType(ContentType.Application.Json); setBody("{\"items\":[{\"productId\":\"product-1\",\"variantId\":\"variant-1\",\"quantity\":2}],\"currency\":\"USD\",\"country\":\"US\",\"customerSegment\":\"VIP\"}") }
        assertEquals(HttpStatusCode.OK, quote.status)
        assertEquals(2, store.lastQuote.single().quantity)
        assertEquals("USD", store.quoteCurrency)

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/admin/pricing") { contentType(ContentType.Application.Json); setBody(priceBody()) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/pricing") { auth(ordinary); contentType(ContentType.Application.Json); setBody(priceBody()) }.status)
        val created = client.post("/api/v1/admin/pricing") { auth(editor); contentType(ContentType.Application.Json); setBody(priceBody()) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("admin-1", store.createdActor)
        val updated = client.patch("/api/v1/admin/pricing/price-1") { auth(editor); contentType(ContentType.Application.Json); setBody(priceBody(saleMinor = 1_800)) }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertTrue(cache.deleted.single().startsWith("pricing:product-1:variant-1:USD:US:VIP"))
        val deleted = client.delete("/api/v1/admin/pricing/price-1") { auth(editor) }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(deleted.bodyAsText().contains("retired"))
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/admin/pricing/price-1") { auth(superAdmin) }.status)
    }

    @Test
    fun `pricing request maps optional effective end and rejects malformed time`() {
        val request = PriceRequest("product-1", "variant-1", "seller-1", "USD", "US", "VIP", 2_000, 1_800, 750, "2026-08-20T00:00:00Z", "2026-12-31T00:00:00Z")
        val input = request.input()
        assertEquals(Instant.parse("2026-12-31T00:00:00Z"), input.effectiveTo)
        assertNull(request.copy(effectiveTo = null).input().effectiveTo)
        assertEquals(request, json.decodeFromString<PriceRequest>(json.encodeToString(request)))
        val sparse = json.decodeFromString<PriceRequest>("{\"productId\":\"product-1\",\"baseMinor\":2000,\"effectiveFrom\":\"2026-08-20T00:00:00Z\"}")
        assertEquals("INR", sparse.currency)
        assertEquals(null, sparse.saleMinor)
        assertFailsWith<Exception> { request.copy(effectiveFrom = "not-a-time").input() }
    }

    private fun io.ktor.server.application.Application.installRoutes(store: PricingStore, cache: PricingCache) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configurePricingRoutes(store, cache, verifier) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun priceBody(saleMinor: Long? = 1_900) = json.encodeToString(PriceRequest("product-1", "variant-1", "seller-1", "USD", "US", "VIP", 2_000, saleMinor, 750, "2026-08-20T00:00:00Z"))

    private fun token(roles: List<String> = emptyList(), permissions: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"admin-1\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakePricingStore : PricingStore {
        var currentCalls = 0
        var currency = ""
        var region = ""
        var segment = ""
        var quoteCurrency = ""
        var lastQuote = emptyList<QuoteItem>()
        var createdActor: String? = null
        override fun current(productId: String, variantId: String?, currency: String, region: String, segment: String): Price? { currentCalls++; this.currency = currency; this.region = region; this.segment = segment; return samplePrice.takeIf { productId == it.productId } }
        override fun quote(items: List<QuoteItem>, currency: String, region: String, segment: String): PriceQuote { lastQuote = items; quoteCurrency = currency; return PriceQuote(listOf(QuoteLine("product-1", "variant-1", 2, 1_800, 3_600, 270)), 3_600, 0, 270, 0, 3_870, currency, "price-1:1") }
        override fun create(input: PriceInput, actorId: String, correlationId: String): Price { createdActor = actorId; return samplePrice }
        override fun update(id: String, input: PriceInput, actorId: String, correlationId: String): Price = samplePrice
        override fun delete(id: String, actorId: String, correlationId: String): Int = 1
    }

    private class FakePricingCache : PricingCache {
        private val values = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        fun seed(key: String, value: String) { values[key] = value }
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String, ttl: Duration) { values[key] = value }
        override fun delete(vararg keys: String) { deleted += keys }
    }

    private companion object {
        val samplePrice = Price("price-1", "product-1", "variant-1", "seller-1", "USD", "US", "VIP", 2_000, 1_800, 750, "2026-08-20T00:00:00Z", null, 1, "seller-1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
    }
}
