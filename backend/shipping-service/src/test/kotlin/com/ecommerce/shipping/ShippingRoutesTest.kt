package com.ecommerce.shipping

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

class ShippingRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))
    private val address = ShippingAddress("Ayyaz", "+919999999999", "1 Main Road", city = "Mumbai", state = "MH", postalCode = "400001", country = "IN")

    @Test
    fun `shipping routes enforce auth, internal token, ownership, and webhook signature`() = testApplication {
        val store = FakeShippingStore()
        application { installRoutes(store) }
        val quote = json.encodeToString(ShippingQuoteRequest(address, listOf(ShipmentItem("v-1", 1)), ShippingMethod.STANDARD, "INR"))
        val shipment = json.encodeToString(ShipmentCreateRequest("order-1", "user-1", address, listOf(ShipmentItem("v-1", 1)), ShippingMethod.STANDARD, 100, "INR"))

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/shipping/quotes") { contentType(ContentType.Application.Json); setBody(quote) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/shipping/quotes") { auth(token()); contentType(ContentType.Application.Json); setBody(quote) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/internal/shipping/shipments") { header("X-Internal-Service-Token", "internal"); contentType(ContentType.Application.Json); setBody(shipment) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/internal/shipping/shipments") { header("X-Internal-Service-Token", "wrong"); header("Idempotency-Key", "ship-1"); contentType(ContentType.Application.Json); setBody(shipment) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/internal/shipping/shipments") { header("X-Internal-Service-Token", "internal"); header("Idempotency-Key", "ship-1"); contentType(ContentType.Application.Json); setBody(shipment) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/shipping/shipments/shp-1") { auth(token()) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/shipping/shipments/missing") { auth(token()) }.status)

        val webhook = "{\"providerEventId\":\"event-1\",\"providerShipmentId\":\"provider-1\",\"status\":\"DELIVERED\",\"trackingNumber\":\"T-1\"}"
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/shipping/webhooks") { header("X-Provider-Signature", "bad"); setBody(webhook) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/shipping/webhooks") { header("X-Provider-Signature", "valid"); setBody(webhook) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/internal/shipping/shipments/missing") { header("X-Internal-Service-Token", "internal") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/internal/shipping/shipments/shp-1") { header("X-Internal-Service-Token", "internal") }.status)
    }

    @Test
    fun `shipping dependency errors preserve typed status`() = testApplication {
        val store = FakeShippingStore().also { it.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "shipping unavailable", 503, retryable = true) }
        application { installRoutes(store) }
        val quote = json.encodeToString(ShippingQuoteRequest(address, listOf(ShipmentItem("v-1", 1)), ShippingMethod.STANDARD, "INR"))
        val response = client.post("/api/v1/shipping/quotes") { auth(token()); contentType(ContentType.Application.Json); setBody(quote) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("DEPENDENCY_UNAVAILABLE"))
    }

    private fun io.ktor.server.application.Application.installRoutes(store: ShippingStore) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing {
            configureShippingRoutes(store, object : ShippingWebhookVerifier {
                override fun verifyWebhook(body: String, signature: String?) = signature == "valid"
            }, verifier, "internal")
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(value: String) = header(HttpHeaders.Authorization, "Bearer $value")

    private fun token(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"user-1\",\"roles\":[],\"permissions\":[],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeShippingStore : ShippingStore {
        var failure: ApiException? = null
        private val response = ShipmentResponse("shp-1", "order-1", "user-1", "fake", "provider-1", ShipmentStatus.IN_TRANSIT, ShippingMethod.STANDARD, "TRACK-1", "Carrier", 100, "INR", "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z")
        override fun quote(userId: String, request: ShippingQuoteRequest): ShippingQuote { failure?.let { throw it }; return ShippingQuote("quote-1", request.method, 100, request.currency, "2026-08-21T00:00:00Z") }
        override fun create(request: ShipmentCreateRequest, key: String, correlation: String) = response
        override fun getOwned(userId: String, id: String) = if (id == response.id && userId == response.userId) response else null
        override fun getInternal(id: String) = if (id == response.id) response else null
        override fun tracking(request: TrackingWebhook, correlation: String) = response.copy(status = request.status, trackingNumber = request.trackingNumber)
    }
}
