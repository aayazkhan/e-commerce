package com.ecommerce.order

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

class OrderRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))

    @Test
    fun `customer and internal routes enforce authentication tokens and idempotency`() = testApplication {
        val store = FakeOrderStore()
        application { installRoutes(store) }
        val request = json.encodeToString(createRequest())
        val user = token()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/orders") { contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/orders") { auth(user); contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/orders") { auth(user); header("X-Internal-Service-Token", "internal"); contentType(ContentType.Application.Json); setBody(request) }.status)
        val created = client.post("/api/v1/orders") { auth(user); header("X-Internal-Service-Token", "internal"); header("Idempotency-Key", "order-key"); contentType(ContentType.Application.Json); setBody(request) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("user-1", store.createdUser)
        assertEquals("order-key", store.createdKey)

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/internal/orders/order-1/status") { header("X-Internal-Service-Token", "wrong"); contentType(ContentType.Application.Json); setBody("{\"status\":\"PAID\"}") }.status)
        val internal = client.post("/api/v1/internal/orders/order-1/status") { header("X-Internal-Service-Token", "internal"); contentType(ContentType.Application.Json); setBody("{\"status\":\"PAID\"}") }
        assertEquals(HttpStatusCode.OK, internal.status)
        assertEquals(OrderStatus.PAID, store.lastStatus)
    }

    @Test
    fun `customer listing ownership cancellation and returns preserve request values`() = testApplication {
        val store = FakeOrderStore()
        application { installRoutes(store) }
        val user = token()

        val list = client.get("/api/v1/orders?cursor=next&limit=4") { auth(user) }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals("next", store.lastCursor)
        assertEquals(4, store.lastLimit)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/orders/order-1") { auth(user) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/orders/missing") { auth(user) }.status)

        val cancelled = client.post("/api/v1/orders/order-1/cancel") { auth(user); contentType(ContentType.Application.Json); setBody("{\"reason\":\"changed mind\"}") }
        assertEquals(HttpStatusCode.OK, cancelled.status)
        assertEquals("changed mind", store.cancelReason)

        val returned = client.post("/api/v1/orders/order-1/returns") { auth(user); contentType(ContentType.Application.Json); setBody(json.encodeToString(ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 1)))) ) }
        assertEquals(HttpStatusCode.Created, returned.status)
        assertEquals("damaged", store.returnRequest?.reason)
    }

    @Test
    fun `admin permissions and repository failures are surfaced`() = testApplication {
        val store = FakeOrderStore()
        application { installRoutes(store) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/orders") { auth(token()) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/orders") { auth(token("ADMIN_ORDER_READ")) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/orders/order-1/status") { auth(token("ADMIN_ORDER_READ")); contentType(ContentType.Application.Json); setBody("{\"status\":\"CONFIRMED\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/orders/order-1/status") { auth(token("ADMIN_ORDER_UPDATE")); contentType(ContentType.Application.Json); setBody("{\"status\":\"CONFIRMED\"}") }.status)

        store.failure = ApiException(ErrorCode.CONFLICT, "invalid transition", 409)
        val failed = client.get("/api/v1/orders/order-1") { auth(token()) }
        assertEquals(HttpStatusCode.Conflict, failed.status)
        assertTrue(failed.bodyAsText().contains("invalid transition"))
    }

    private fun createRequest() = OrderCreateRequest(
        checkoutId = "checkout-1",
        reservationId = "reservation-1",
        items = listOf(OrderItemSnapshot("product-1", "variant-1", "Product", quantity = 1, unitPriceMinor = 1_000, taxMinor = 180, discountMinor = 0, lineTotalMinor = 1_000, currency = "INR")),
        shippingAddress = address(),
        billingAddress = address(),
        subtotalMinor = 1_000,
        itemDiscountMinor = 0,
        promotionDiscountMinor = 0,
        shippingMinor = 50,
        taxMinor = 180,
        totalMinor = 1_230,
        currency = "INR",
    )

    private fun address() = AddressSnapshot("address-1", "Customer", "+911234567890", "Line 1", city = "Pune", state = "MH", postalCode = "411001", country = "IN")

    private fun io.ktor.server.application.Application.installRoutes(store: OrderStore) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureOrderRoutes(store, verifier, "internal") }
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

    private class FakeOrderStore : OrderStore {
        var createdUser: String? = null
        var createdKey: String? = null
        var lastCursor: String? = null
        var lastLimit: Int? = null
        var lastStatus: OrderStatus? = null
        var cancelReason: String? = null
        var returnRequest: ReturnRequest? = null
        var failure: ApiException? = null
        private val order = OrderResponse("order-1", "user-1", "checkout-1", "reservation-1", OrderStatus.CREATED, 1_000, 0, 0, 50, 180, 1_230, "INR", emptyList(), AddressSnapshot("address-1", "Customer", "+911234567890", "Line 1", city = "Pune", state = "MH", postalCode = "411001", country = "IN"), AddressSnapshot("address-1", "Customer", "+911234567890", "Line 1", city = "Pune", state = "MH", postalCode = "411001", country = "IN"), null, 1, "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z")
        override fun create(userId: String, request: OrderCreateRequest, idempotencyKey: String, actorId: String, correlationId: String): OrderResponse { createdUser = userId; createdKey = idempotencyKey; return order }
        override fun getOwned(userId: String, id: String): OrderResponse? { failure?.let { throw it }; return order.takeIf { userId == it.userId && id == it.id } }
        override fun listOwned(userId: String, cursor: String?, limit: Int) = listOf(order) to "next".also { lastCursor = cursor; lastLimit = limit }
        override fun listAll(cursor: String?, limit: Int) = listOf(order) to null
        override fun transition(id: String, userId: String?, target: OrderStatus, actorId: String, reason: String?, correlationId: String): OrderResponse { lastStatus = target; return order.copy(status = target) }
        override fun cancel(id: String, userId: String, reason: String, actorId: String, correlationId: String): OrderResponse { cancelReason = reason; return order.copy(status = OrderStatus.CANCEL_REQUESTED) }
        override fun requestReturn(id: String, userId: String, request: ReturnRequest, actorId: String, correlationId: String): ReturnResponse { returnRequest = request; return ReturnResponse("return-1", id, "REQUESTED", request.reason, request.items, "2026-08-21T00:00:00Z") }
    }
}
