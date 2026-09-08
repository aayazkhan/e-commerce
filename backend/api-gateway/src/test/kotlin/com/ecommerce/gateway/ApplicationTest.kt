package com.ecommerce.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `live endpoint returns request id and healthy response`() = testApplication {
        application { module() }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers.contains("X-Request-Id"))
        assertTrue(response.bodyAsText().contains("api-gateway"))
    }

    @Test
    fun `CORS preflight allows the mutating methods this gateway actually proxies`() = testApplication {
        application { module() }

        // Ktor's CORS plugin only allows GET/POST/HEAD unless every other method is explicitly
        // registered -- a real browser preflight for editing/deleting a seller product (or any
        // other PATCH/DELETE route this gateway forwards) was silently rejected with 403 before
        // the actual request was ever sent, even though the downstream service allowed it fine.
        // No prior test ever issued a real OPTIONS preflight, so this went undetected.
        for (method in listOf("PATCH", "PUT", "DELETE")) {
            val preflight = client.options("/api/v1/seller/products/prd-1") {
                header(HttpHeaders.Origin, "http://localhost:3000")
                header("Access-Control-Request-Method", method)
                header("Access-Control-Request-Headers", "authorization,content-type")
            }
            assertEquals(HttpStatusCode.OK, preflight.status, "preflight for $method")
            assertTrue(preflight.headers["Access-Control-Allow-Methods"]?.contains(method) == true, "Allow-Methods missing $method")
        }
    }

    @Test
    fun `proxies a public resource path to its owning service and passes through the body`() = testApplication {
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"id":"cart-1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        application { module(HttpClient(mockEngine)) }

        val response = client.get("/api/v1/cart") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"id":"cart-1"}""", response.bodyAsText())
        assertEquals("http://cart-service:8088/api/v1/cart", capturedUrl)
        assertEquals("Bearer test-token", capturedAuth)
    }

    @Test
    fun `does not forward the browser's Origin header to the downstream service`() = testApplication {
        var capturedOrigin: String? = null
        val mockEngine = MockEngine { request ->
            capturedOrigin = request.headers[HttpHeaders.Origin]
            respond(
                content = """{"id":"cart-1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        application { module(HttpClient(mockEngine)) }

        // CORS is a browser<->gateway contract, already enforced by the gateway's own CORS
        // plugin before this request reaches ProxyHandler. Forwarding Origin downstream makes
        // every proxied service's OWN CORS plugin (installed for direct-access scenarios) also
        // gate the request against its own, narrower method allowlist -- this silently 403'd
        // every PATCH/DELETE call the gateway proxied, even with the gateway's CORS config fixed.
        val response = client.get("/api/v1/cart") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(null, capturedOrigin)
    }

    @Test
    fun `routes an admin sub-resource path to its owning service, not admin-service`() = testApplication {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(content = "[]", status = HttpStatusCode.OK)
        }
        application { module(HttpClient(mockEngine)) }

        client.get("/api/v1/admin/orders")

        assertEquals("http://order-service:8091/api/v1/admin/orders", capturedUrl)
    }

    @Test
    fun `routes the admin catch-all to admin-service when no more specific resource matches`() = testApplication {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        application { module(HttpClient(mockEngine)) }

        client.get("/api/v1/admin/dashboard")

        assertEquals("http://admin-service:8104/api/v1/admin/dashboard", capturedUrl)
    }

    @Test
    fun `does not route internal service-to-service paths through the public gateway`() = testApplication {
        val mockEngine = MockEngine { respond(content = "", status = HttpStatusCode.OK) }
        application { module(HttpClient(mockEngine)) }

        val response = client.post("/api/v1/internal/payments") {
            setBody("{}")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `returns not found for a path that matches no service`() = testApplication {
        val mockEngine = MockEngine { respond(content = "", status = HttpStatusCode.OK) }
        application { module(HttpClient(mockEngine)) }

        val response = client.get("/api/v1/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `forwards request body and method for a write request`() = testApplication {
        var capturedMethod: String? = null
        var capturedBody: String? = null
        val mockEngine = MockEngine { request ->
            capturedMethod = request.method.value
            capturedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(content = """{"created":true}""", status = HttpStatusCode.Created)
        }
        application { module(HttpClient(mockEngine)) }

        val response = client.post("/api/v1/reviews") {
            contentType(ContentType.Application.Json)
            setBody("""{"rating":5}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("POST", capturedMethod)
        assertEquals("""{"rating":5}""", capturedBody)
    }

    @Test
    fun `ready endpoint reports healthy`() = testApplication {
        application { module() }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("api-gateway"))
    }

    @Test
    fun `metrics endpoint returns plaintext gauge`() = testApplication {
        application { module() }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("gateway_up 1"))
    }

    @Test
    fun `accepts a caller-supplied request id within the allowed length`() = testApplication {
        application { module() }

        val response = client.get("/health/live") {
            header(HttpHeaders.XRequestId, "12345678")
        }

        assertEquals("12345678", response.headers["X-Request-Id"])
    }

    @Test
    fun `regenerates a caller-supplied request id that is too short`() = testApplication {
        application { module() }

        val response = client.get("/health/live") {
            header(HttpHeaders.XRequestId, "abc")
        }

        assertTrue(response.headers["X-Request-Id"] != "abc")
    }

    @Test
    fun `unexpected downstream failure is mapped to a safe internal error response`() = testApplication {
        val mockEngine = MockEngine { throw IllegalStateException("downstream exploded") }
        application { module(HttpClient(mockEngine)) }

        val response = client.get("/api/v1/cart")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("unexpected error"))
    }

    @Test
    fun `health response value semantics and json round trip`() {
        val a = HealthResponse(status = "UP", component = "api-gateway")
        val b = a.copy()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("UP"))
        assertEquals("UP", a.status)
        assertEquals("api-gateway", a.component)

        val json = kotlinx.serialization.json.Json.encodeToString(HealthResponse.serializer(), a)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(HealthResponse.serializer(), json)
        assertEquals(a, decoded)
    }

    @Test
    fun `propagates a non-hop-by-hop response header from the downstream service`() = testApplication {
        val mockEngine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf("X-Downstream-Trace", "trace-123"),
            )
        }
        application { module(HttpClient(mockEngine)) }

        val response = client.get("/api/v1/cart")

        assertEquals("trace-123", response.headers["X-Downstream-Trace"])
    }

    @Test
    fun `propagates an inbound traceparent header into logging context without failing the request`() = testApplication {
        application { module() }

        val response = client.get("/health/live") {
            header("traceparent", "00-abc-def-01")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
