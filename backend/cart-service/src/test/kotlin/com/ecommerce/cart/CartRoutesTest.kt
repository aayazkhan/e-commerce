package com.ecommerce.cart

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
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CartRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))

    @Test
    fun `guest cart uses cache, enforces stock and supports mutations`() = testApplication {
        val store = FakeCartStore()
        val inventory = FakeCartInventory(available = 10)
        val cache = FakeCartCache()
        application { installRoutes(store, FakeCartPricing(), inventory, cache) }
        val first = client.get("/api/v1/cart?currency=INR")
        assertEquals(HttpStatusCode.OK, first.status)
        val guest = json.decodeFromString<CartResponse>(first.bodyAsText()).guestToken!!
        assertEquals(1, store.getCalls)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cart?currency=INR") { header("X-Guest-Token", guest) }.status)
        assertEquals(1, store.getCalls)

        val addBody = "{\"productId\":\"product-1\",\"variantId\":\"variant-1\",\"quantity\":1}"
        val noKey = client.post("/api/v1/cart/items") { header("X-Guest-Token", guest); contentType(ContentType.Application.Json); setBody(addBody) }
        assertEquals(HttpStatusCode.BadRequest, noKey.status)
        val added = client.post("/api/v1/cart/items") { header("X-Guest-Token", guest); header("Idempotency-Key", "add-1"); contentType(ContentType.Application.Json); setBody(addBody) }
        assertEquals(HttpStatusCode.Created, added.status)
        assertEquals("variant-1", store.lastVariant)
        assertTrue(cache.deleted.isNotEmpty())

        val updated = client.patch("/api/v1/cart/items/variant-1") { header("X-Guest-Token", guest); header("Idempotency-Key", "update-1"); contentType(ContentType.Application.Json); setBody("{\"quantity\":2}") }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(2, store.lastQuantity)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/cart/items/variant-1") { header("X-Guest-Token", guest); header("Idempotency-Key", "remove-1") }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/cart/items") { header("X-Guest-Token", guest); header("Idempotency-Key", "clear-1") }.status)
    }

    @Test
    fun `cart routes cover authenticated merge, validation warnings and dependency conflicts`() = testApplication {
        val store = FakeCartStore()
        val inventory = FakeCartInventory(available = 1)
        application { installRoutes(store, FakeCartPricing(unitMinor = 120), inventory, FakeCartCache()) }
        val user = token("user-1")
        val validation = client.post("/api/v1/cart/validate") { auth(user) }
        assertEquals(HttpStatusCode.OK, validation.status)
        assertTrue(validation.bodyAsText().contains("PRICE_CHANGED"))
        assertTrue(validation.bodyAsText().contains("INSUFFICIENT_STOCK"))
        val conflict = client.post("/api/v1/cart/items") { auth(user); header("Idempotency-Key", "add-conflict"); contentType(ContentType.Application.Json); setBody("{\"productId\":\"product-1\",\"variantId\":\"variant-1\",\"quantity\":2}") }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        val merged = client.post("/api/v1/cart/merge") { auth(user); contentType(ContentType.Application.Json); setBody("{\"guestToken\":\"guest-token-123456\"}") }
        assertEquals(HttpStatusCode.OK, merged.status)
        assertEquals("user-1", store.mergedUser)
        val unauthenticatedMerge = client.post("/api/v1/cart/merge") { contentType(ContentType.Application.Json); setBody("{\"guestToken\":\"guest-token-123456\"}") }
        assertEquals(HttpStatusCode.Unauthorized, unauthenticatedMerge.status)
    }

    @Test
    fun `guest token and owned item failures map to authentication and not found`() = testApplication {
        application { installRoutes(FakeCartStore(), FakeCartPricing(), FakeCartInventory(10), FakeCartCache()) }
        assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/cart/items/item-1") { header("X-Guest-Token", "short"); header("Idempotency-Key", "key"); contentType(ContentType.Application.Json); setBody("{\"quantity\":1}") }.status)
        val user = token("user-1")
        val missing = client.delete("/api/v1/cart/items/missing") { auth(user); header("Idempotency-Key", "key") }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `authenticated and explicit guest actors cover currency cache and id based mutations`() = testApplication {
        val store = FakeCartStore()
        val cache = FakeCartCache()
        application { installRoutes(store, FakeCartPricing(), FakeCartInventory(10), cache) }
        val user = token("user-1")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cart") { auth(user); header("X-Currency", "usd") }.status)

        val addBody = "{\"productId\":\"product-1\",\"variantId\":\"variant-1\",\"quantity\":1}"
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/cart/items") {
            auth(user); header("Idempotency-Key", " "); contentType(ContentType.Application.Json); setBody(addBody)
        }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/cart/items") {
            auth(user); header("Idempotency-Key", "auth-add"); contentType(ContentType.Application.Json); setBody(addBody)
        }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/cart/items/item-1") {
            auth(user); header("Idempotency-Key", "auth-update"); contentType(ContentType.Application.Json); setBody("{\"quantity\":1}")
        }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/cart/items/item-1") {
            auth(user); header("Idempotency-Key", "auth-remove")
        }.status)
        assertTrue(client.post("/api/v1/cart/validate") { auth(user) }.bodyAsText().contains("\"valid\":true"))

        val guest = "guest-token-123456"
        cache.forced = "not-json"
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cart") { header("X-Guest-Token", guest) }.status)
        cache.forced = json.encodeToString(sample.copy(currency = "USD"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cart") { header("X-Guest-Token", guest) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/cart/items/item-1") {
            header("Idempotency-Key", "missing-auth"); contentType(ContentType.Application.Json); setBody("{\"quantity\":1}")
        }.status)
    }

    private fun io.ktor.server.application.Application.installRoutes(store: CartStore, pricing: CartPricing, inventory: CartInventory, cache: CartCache) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureCartRoutes(store, pricing, inventory, cache, verifier) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun token(subject: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"$subject\",\"roles\":[],\"permissions\":[],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeCartStore : CartStore {
        var getCalls = 0
        var lastVariant: String? = null
        var lastQuantity = 0
        var mergedUser: String? = null
        override fun getOrCreate(actor: CartActor, currency: String): CartResponse { getCalls++; return sample }
        override fun add(actor: CartActor, productId: String, variantId: String, quantity: Int, price: PriceSnapshot, key: String, correlationId: String): CartResponse { lastVariant = variantId; lastQuantity = quantity; return sample.copy(items = listOf(sampleItem.copy(quantity = quantity, unitPriceMinor = price.unitMinor))) }
        override fun update(actor: CartActor, variantId: String, quantity: Int, price: PriceSnapshot?, key: String, correlationId: String): CartResponse { lastQuantity = quantity; return sample.copy(items = listOf(sampleItem.copy(quantity = quantity))) }
        override fun remove(actor: CartActor, variantId: String, currency: String, key: String, correlationId: String) = sample.copy(items = emptyList())
        override fun clear(actor: CartActor, currency: String, key: String, correlationId: String) = sample.copy(items = emptyList())
        override fun merge(userId: String, guestToken: String, currency: String, correlationId: String): CartResponse { mergedUser = userId; return sample.copy(userId = userId, guestToken = null) }
    }

    private class FakeCartPricing(private val unitMinor: Long = 100) : CartPricing { override fun current(productId: String, variantId: String, currency: String) = PriceSnapshot(unitMinor, currency, "v1") }
    private class FakeCartInventory(private val available: Long) : CartInventory { override fun available(variantId: String) = available }
    private class FakeCartCache : CartCache {
        private val values = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var forced: String? = null
        override fun get(key: String) = forced ?: values[key]
        override fun put(key: String, value: String, ttl: java.time.Duration) { values[key] = value }
        override fun delete(vararg keys: String) { deleted += keys; keys.forEach(values::remove) }
    }

    private companion object {
        val sampleItem = CartItem("item-1", "product-1", "variant-1", 2, 100, "INR", "v1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val sample = CartResponse("cart-1", null, "INR", 1, listOf(sampleItem))
    }
}
