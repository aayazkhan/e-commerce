package com.ecommerce.seller

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.service.DownstreamResponse
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

class SellerRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))
    private val config = SellerRouteConfig("catalog", "inventory", "promotion", "analytics", "identity", "internal-secret")

    @Test
    fun `seller routes enforce identity, ownership and proxy downstream calls`() = testApplication {
        val store = FakeSellerStore()
        val proxy = FakeSellerProxy()
        application { installRoutes(store, proxy) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/sellers/seller-1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/seller/profile").status)
        val sellerToken = token("user-1")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/profile") { auth(sellerToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/products?limit=2") { auth(sellerToken) }.status)
        assertEquals("catalog", proxy.lastBase)
        // sellerId here must be the JWT subject (user-1), not the seller-service record id
        // (seller-1) -- catalog-service always persists sellerId as the caller's principal.subject
        // for ownerType=SELLER (see catalog-service Application.kt), so seller-service has to
        // query/compare using that same identity or list/ownership checks silently mismatch.
        assertTrue(proxy.lastPath!!.contains("sellerId=user-1"))
        val product = client.post("/api/v1/seller/products") { auth(sellerToken); contentType(ContentType.Application.Json); setBody("{\"name\":\"Shoe\"}") }
        assertEquals(HttpStatusCode.OK, product.status)
        assertTrue(proxy.lastBody!!.contains("\"ownerType\":\"SELLER\""))
        assertTrue(proxy.lastBody!!.contains("\"sellerId\":\"user-1\""))
        val invalid = client.post("/api/v1/seller/products") { auth(sellerToken); contentType(ContentType.Application.Json); setBody("not-json") }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)

        proxy.response = DownstreamResponse(200, "{\"sellerId\":\"user-1\"}")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/products/product-1") { auth(sellerToken) }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/seller/products/product-1") { auth(sellerToken); contentType(ContentType.Application.Json); setBody("{\"name\":\"Updated\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/seller/products/product-1") { auth(sellerToken) }.status)
        proxy.response = DownstreamResponse(200, "{\"sellerId\":\"seller-2\"}")
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/seller/products/product-1") { auth(sellerToken); contentType(ContentType.Application.Json); setBody("{}") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/seller/products/product-1") { auth(sellerToken) }.status)
        proxy.response = DownstreamResponse(200, "{}")
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/seller/products/product-1") { auth(sellerToken); contentType(ContentType.Application.Json); setBody("{}") }.status)
        proxy.response = DownstreamResponse(404, "missing")
        assertEquals(HttpStatusCode.NotFound, client.patch("/api/v1/seller/products/product-1") { auth(sellerToken); contentType(ContentType.Application.Json); setBody("{}") }.status)
    }

    @Test
    fun `seller data routes cover lifecycle, ledger, inventory validation and admin controls`() = testApplication {
        val store = FakeSellerStore()
        val proxy = FakeSellerProxy()
        application { installRoutes(store, proxy) }
        val user = token("user-1")
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/seller/applications") { auth(user); contentType(ContentType.Application.Json); setBody("{\"displayName\":\"Shop\",\"legalName\":\"Shop Ltd\",\"email\":\"shop@example.com\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/seller/profile") { auth(user); contentType(ContentType.Application.Json); setBody("{\"displayName\":\"Updated\",\"legalName\":\"Shop Ltd\",\"version\":1}") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/orders") { auth(user) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/orders/order-1") { auth(user) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/seller/orders/missing") { auth(user) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/seller/inventory") { auth(user) }.status)
        proxy.response = DownstreamResponse(200, "{\"sellerId\":\"user-1\"}")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/inventory?variantId=variant-1&productId=product-1") { auth(user) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/promotions?limit=500") { auth(user) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/analytics") { auth(user) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/seller/ledger") { auth(user) }.status)

        val admin = token("admin-1", roles = listOf("ADMIN"))
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/sellers/seller-1/status") { auth(admin); contentType(ContentType.Application.Json); setBody("{\"status\":\"ACTIVE\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/sellers/seller-1/ledger") { auth(admin); contentType(ContentType.Application.Json); setBody("{\"entryType\":\"SALE\",\"referenceId\":\"order-1\",\"amountMinor\":1000}") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/sellers/missing").status)
    }

    @Test
    fun `seller routes deny inactive owners and map downstream and admin failures`() = testApplication {
        val store = FakeSellerStore()
        val proxy = FakeSellerProxy()
        application { installRoutes(store, proxy) }

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/seller/profile") { auth(token("user-2")) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/seller/products") { auth(token("user-2")) }.status)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/seller/inventory?variantId=variant-1") { auth(token("user-1")) }.status)
        proxy.response = DownstreamResponse(503, "provider unavailable")
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/seller/products") { auth(token("user-1")) }.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/seller/analytics") { auth(token("user-1")) }.status)
        client.get("/api/v1/seller/promotions?limit=invalid") { auth(token("user-1")) }
        assertTrue(proxy.lastPath!!.contains("limit=50"))

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/sellers/seller-1/status") {
            auth(token("user-1")); contentType(ContentType.Application.Json); setBody("{\"status\":\"ACTIVE\"}")
        }.status)
        assertEquals(HttpStatusCode.InternalServerError, client.post("/api/v1/admin/sellers/seller-1/status") {
            auth(token("admin-1", roles = listOf("ADMIN"))); contentType(ContentType.Application.Json); setBody("{\"status\":\"not-a-status\"}")
        }.status)
        assertEquals(HttpStatusCode.BadGateway, client.post("/api/v1/admin/sellers/seller-1/status") {
            auth(token("operator", permissions = listOf("ADMIN_SELLER_UPDATE"))); contentType(ContentType.Application.Json); setBody("{\"status\":\"ACTIVE\"}")
        }.status)

        proxy.response = DownstreamResponse(200, "{\"ok\":true}")
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/sellers/seller-1/status") {
            auth(token("operator", permissions = listOf("ADMIN_SELLER_UPDATE"))); contentType(ContentType.Application.Json); setBody("{\"status\":\"ACTIVE\"}")
        }.status)
    }

    private fun io.ktor.server.application.Application.installRoutes(store: SellerStore, proxy: SellerProxy) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureSellerRoutes(store, proxy, verifier, config, json) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun token(subject: String, roles: List<String> = emptyList(), permissions: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"$subject\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeSellerStore : SellerStore {
        override fun byUser(user: String) = sample.takeIf { user == "user-1" }
        override fun get(id: String) = sample.takeIf { id == it.id }
        override fun create(owner: String, request: SellerApplication, actor: String, correlation: String) = sample
        override fun update(user: String, request: SellerProfileUpdate) = sample.copy(displayName = request.displayName)
        override fun orders(seller: String, limit: Int) = listOf(sampleOrder)
        override fun ledger(seller: String) = listOf(sampleLedger)
        override fun transition(id: String, target: SellerStatus, actor: String, reason: String?, correlation: String) = sample.copy(status = target)
        override fun addLedger(seller: String, request: LedgerEntryRequest, actor: String) = sampleLedger.copy(entryType = request.entryType, referenceId = request.referenceId)
    }

    private class FakeSellerProxy : SellerProxy {
        var response = DownstreamResponse(200, "{\"ok\":true}")
        var lastBase: String? = null
        var lastPath: String? = null
        var lastBody: String? = null
        override fun request(baseUrl: String, method: String, path: String, bearer: String?, body: String?, requestId: String?, internalToken: String?, actorId: String?): DownstreamResponse { lastBase = baseUrl; lastPath = path; lastBody = body; return response }
    }

    private companion object {
        val sample = SellerResponse("seller-1", "user-1", "Shop", "Shop Ltd", "shop@example.com", null, SellerStatus.ACTIVE, 1, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val sampleOrder = SellerOrderItem("order-1", "product-1", "variant-1", 2, 2_000, "INR", "PAID", "2026-08-20T00:00:00Z")
        val sampleLedger = LedgerEntry("ledger-1", LedgerEntryType.SALE, "order-1", 2_000, "INR", "sale", "2026-08-20T00:00:00Z")
    }
}
