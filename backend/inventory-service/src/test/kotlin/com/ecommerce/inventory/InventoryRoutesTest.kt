package com.ecommerce.inventory

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.get
import io.ktor.client.request.header
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

class InventoryRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))

    @Test
    fun `inventory routes cover public reads, internal reservation ownership and lifecycle`() = testApplication {
        val store = FakeInventoryStore()
        application { installRoutes(store) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/inventory/items/variant-1").status)
        val request = json.encodeToString(ReservationRequest("reserve-1", "cart-1", "order-1", listOf(ReservationItem("variant-1", "warehouse-1", 2)), 600))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/inventory/reservations") { contentType(ContentType.Application.Json); setBody(request) }.status)
        val reserved = client.post("/api/v1/inventory/reservations") { header("X-Internal-Service-Token", "internal-secret"); header("X-Actor-Id", "checkout-1"); contentType(ContentType.Application.Json); setBody(request) }
        assertEquals(HttpStatusCode.Created, reserved.status)
        assertEquals("checkout-1", store.reservationActor)
        assertEquals(600L, store.reservationTtl)

        val owner = token("checkout-1", permissions = listOf("INVENTORY_READ"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/inventory/reservations/reservation-1") { auth(owner) }.status)
        val other = token("other-user", permissions = listOf("INVENTORY_READ"))
        val forbidden = client.get("/api/v1/inventory/reservations/reservation-1") { auth(other) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        val warehouse = token("other-user", permissions = listOf("INVENTORY_READ"), roles = listOf("WAREHOUSE"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/inventory/reservations/reservation-1") { auth(warehouse) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/inventory/reservations/reservation-1") { auth(token("admin", roles = listOf("ADMIN"))) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/inventory/reservations/reservation-1") { auth(token("super-admin", roles = listOf("SUPER_ADMIN"))) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/inventory/reservations/missing") { auth(owner) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/inventory/reservations/reservation-1/release") { header("X-Internal-Service-Token", "internal-secret"); header("X-Actor-Id", "checkout-1") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/inventory/reservations/reservation-1/commit") { header("X-Internal-Service-Token", "internal-secret"); header("X-Actor-Id", "checkout-1") }.status)
    }

    @Test
    fun `admin inventory routes validate permissions, map requests and clamp movement query`() = testApplication {
        val store = FakeInventoryStore()
        application { installRoutes(store) }
        val adjuster = token("admin-1", permissions = listOf("INVENTORY_ADJUST", "INVENTORY_READ"))
        val itemBody = json.encodeToString(InventoryItemRequest("warehouse-1", "WH1", "Main", "product-1", "variant-1", 25, 3))
        val created = client.post("/api/v1/admin/inventory/items") { auth(adjuster); contentType(ContentType.Application.Json); setBody(itemBody) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(25L, store.createdInput?.onHand)
        val adjustment = client.post("/api/v1/admin/inventory/adjustments") { auth(adjuster); contentType(ContentType.Application.Json); setBody("{\"itemId\":\"item-1\",\"quantityDelta\":5,\"type\":\"RESTOCK\",\"reason\":\"purchase\"}") }
        assertEquals(HttpStatusCode.OK, adjustment.status)
        assertEquals(5L, store.adjustmentDelta)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/inventory/variant-1") { auth(adjuster) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/inventory/variant-1/movements?limit=999") { auth(adjuster) }.status)
        assertEquals(999, store.movementLimit)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/inventory/variant-1/movements?limit=invalid") { auth(adjuster) }.status)
        assertEquals(50, store.movementLimit)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/inventory/variant-1/movements") { auth(adjuster) }.status)
        assertEquals(50, store.movementLimit)
        val denied = client.post("/api/v1/admin/inventory/items") { auth(token("user-1")); contentType(ContentType.Application.Json); setBody(itemBody) }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
    }

    @Test
    fun `internal reservation requires actor id even with matching service token`() = testApplication {
        application { installRoutes(FakeInventoryStore()) }
        val request = json.encodeToString(ReservationRequest("reserve-1", items = listOf(ReservationItem("variant-1", "warehouse-1", 1))))
        val response = client.post("/api/v1/inventory/reservations") { header("X-Internal-Service-Token", "internal-secret"); contentType(ContentType.Application.Json); setBody(request) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
    }

    @Test
    fun `inventory permission fallback covers authenticated users and privileged warehouse roles`() = testApplication {
        val store = FakeInventoryStore()
        application { installRoutes(store) }
        val request = json.encodeToString(ReservationRequest("reserve-2", items = listOf(ReservationItem("variant-1", "warehouse-1", 1))))
        val user = token("user-1", permissions = listOf("INVENTORY_RESERVE"))
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/inventory/reservations") { auth(user); contentType(ContentType.Application.Json); setBody(request) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/inventory/reservations/reservation-1/release") { auth(user) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/inventory/reservations/reservation-1/commit") { auth(token("user-1", permissions = listOf("INVENTORY_COMMIT"))) }.status)

        val warehouse = token("warehouse-1", roles = listOf("WAREHOUSE"))
        val itemBody = json.encodeToString(InventoryItemRequest(productId = "product-1", variantId = "variant-1", onHand = 10))
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/admin/inventory/items") { auth(warehouse); contentType(ContentType.Application.Json); setBody(itemBody) }.status)
    }

    @Test
    fun `blank internal token configuration falls back to user permission`() = testApplication {
        val store = FakeInventoryStore()
        application { installRoutes(store, internalToken = "") }
        val request = json.encodeToString(ReservationRequest("reserve-3", items = listOf(ReservationItem("variant-1", "warehouse-1", 1))))
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/inventory/reservations") { auth(token("user-1", permissions = listOf("INVENTORY_RESERVE"))); contentType(ContentType.Application.Json); setBody(request) }.status)
    }

    @Test
    fun `wrong internal token falls back to the authenticated reservation permission`() = testApplication {
        val store = FakeInventoryStore()
        application { installRoutes(store) }
        val request = json.encodeToString(ReservationRequest("reserve-wrong-token", items = listOf(ReservationItem("variant-1", "warehouse-1", 1))))
        val response = client.post("/api/v1/inventory/reservations") {
            auth(token("user-1", permissions = listOf("INVENTORY_RESERVE")))
            header("X-Internal-Service-Token", "not-the-configured-token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("user-1", store.reservationActor)
    }

    @Test
    fun `protected inventory routes enforce authentication and map downstream failures`() = testApplication {
        val store = FakeInventoryStore()
        application { installRoutes(store) }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/inventory/reservations/reservation-1/release").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/inventory/reservations/reservation-1/commit").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/admin/inventory/adjustments") {
                contentType(ContentType.Application.Json)
                setBody("{\"itemId\":\"item-1\",\"quantityDelta\":1,\"type\":\"RESTOCK\",\"reason\":\"stock\"}")
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/admin/inventory/variant-1/movements").status,
        )

        store.failure = ApiException(ErrorCode.CONFLICT, "Reservation cannot be released.", 409)
        val conflict = client.post("/api/v1/inventory/reservations/reservation-1/release") {
            auth(token("user-1", permissions = listOf("INVENTORY_RESERVE")))
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertTrue(conflict.bodyAsText().contains("CONFLICT"))

        store.failure = IllegalStateException("database unavailable")
        val failure = client.post("/api/v1/inventory/reservations") {
            header("X-Internal-Service-Token", "internal-secret")
            header("X-Actor-Id", "checkout-1")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ReservationRequest("failure", items = listOf(ReservationItem("variant-1", "warehouse-1", 1)))))
        }
        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        assertTrue(failure.bodyAsText().contains("INTERNAL_ERROR"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: InventoryStore, internalToken: String = "internal-secret") {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureInventoryRoutes(store, verifier, internalToken) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun token(subject: String, permissions: List<String> = emptyList(), roles: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"$subject\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeInventoryStore : InventoryStore {
        var failure: Throwable? = null
        var reservationActor: String? = null
        var reservationTtl = 0L
        var createdInput: InventoryItemInput? = null
        var adjustmentDelta = 0L
        var movementLimit = 0
        override fun findByVariant(variantId: String): List<InventoryItem> { fail(); return listOf(sampleItem) }
        override fun reserve(input: ReservationInput, correlationId: String): Reservation { fail(); reservationActor = input.actorId; reservationTtl = input.ttlSeconds; return sampleReservation.copy(actorId = input.actorId) }
        override fun getReservation(id: String): Reservation? { fail(); return sampleReservation.takeIf { id == it.id } }
        override fun release(id: String, actorId: String, correlationId: String, expired: Boolean): Reservation { fail(); return sampleReservation.copy(status = if (expired) ReservationStatus.EXPIRED else ReservationStatus.RELEASED) }
        override fun commit(id: String, actorId: String, correlationId: String): Reservation { fail(); return sampleReservation.copy(status = ReservationStatus.COMMITTED) }
        override fun createItem(input: InventoryItemInput, actorId: String, correlationId: String): InventoryItem { fail(); createdInput = input; return sampleItem }
        override fun adjust(itemId: String, delta: Long, type: AdjustmentType, reason: String, actorId: String, correlationId: String): InventoryItem { fail(); adjustmentDelta = delta; return sampleItem.copy(onHand = sampleItem.onHand + delta, available = sampleItem.available + delta) }
        override fun movements(variantId: String, limit: Int): List<InventoryMovement> { fail(); movementLimit = limit; return listOf(InventoryMovement("movement-1", "RESTOCK", 5, 105, 0, 105, "item-1", "admin-1", "2026-08-20T00:00:00Z")) }
        private fun fail() { failure?.let { throw it } }
    }

    private companion object {
        val sampleItem = InventoryItem("item-1", "warehouse-1", "product-1", "variant-1", 100, 0, 100, 5, 1, "2026-08-20T00:00:00Z")
        val sampleReservation = Reservation("reservation-1", "reserve-1", "checkout-1", "cart-1", "order-1", ReservationStatus.ACTIVE, "2026-08-20T01:00:00Z", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z", listOf(ReservationItem("variant-1", "warehouse-1", 1)))
    }
}
