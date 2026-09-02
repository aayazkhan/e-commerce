package com.ecommerce.platform.service

import com.ecommerce.platform.error.ApiException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpClientBoundaryTest {
    @Test
    fun `internal client supports get post headers and response mapping`() {
        val server = testServer { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = "${exchange.requestMethod}|${exchange.requestHeaders.getFirst("X-Request-Id") ?: ""}|$body"
            exchange.respond(201, response)
        }
        try {
            val client = InternalHttpClient("http://127.0.0.1:${server.address.port}", Duration.ofSeconds(2))
            assertEquals(201, client.get("/health", mapOf("X-Request-Id" to "req-1")).statusCode)
            assertEquals("GET|req-1|", client.get("/health", mapOf("X-Request-Id" to "req-1")).body)
            assertEquals("POST||payload", client.post("/events", "payload").body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `downstream client maps methods headers and content type`() {
        val server = testServer { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = "${exchange.requestMethod}|${exchange.requestHeaders.getFirst("Authorization") ?: ""}|${exchange.requestHeaders.getFirst("X-Actor-Id") ?: ""}|$body"
            exchange.responseHeaders.add("Content-Type", "application/problem+json")
            exchange.respond(202, response)
        }
        try {
            val client = DownstreamHttpClient(Duration.ofSeconds(2))
            val result = client.request("http://127.0.0.1:${server.address.port}", "PATCH", "/orders", "jwt", "body", actorId = "actor-1")
            assertEquals(202, result.status)
            assertEquals("application/problem+json", result.contentType)
            assertEquals("PATCH|Bearer jwt|actor-1|body", result.body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `downstream client supports every request method and optional boundary headers`() {
        val server = testServer { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = listOf(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("X-Request-Id") ?: "",
                exchange.requestHeaders.getFirst("X-Internal-Service-Token") ?: "",
                exchange.requestHeaders.getFirst("X-Actor-Id") ?: "",
                body,
            ).joinToString("|")
            exchange.respond(200, response)
        }
        try {
            val client = DownstreamHttpClient(Duration.ofSeconds(2))
            val base = "http://127.0.0.1:${server.address.port}/"

            assertEquals("POST|/post|req|internal|actor|payload", client.request(base, "post", "/post", requestId = "req", internalToken = "internal", actorId = "actor", body = "payload").body)
            assertEquals("PUT|/put||||payload", client.request(base, "PUT", "/put", body = "payload").body)
            assertEquals("PATCH|/patch||||payload", client.request(base, "PATCH", "/patch", body = "payload").body)
            assertEquals("DELETE|/delete||||", client.request(base, "DELETE", "/delete", body = "ignored").body)
            assertEquals("GET|/get||||", client.request(base, "HEAD", "/get", body = "ignored").body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `downstream transport failure is counted by the circuit breaker`() {
        val client = DownstreamHttpClient(Duration.ofMillis(50))
        repeat(5) {
            assertFailsWith<Exception> {
                client.request("http://127.0.0.1:1", "GET", "/unavailable")
            }
        }
        assertFailsWith<CircuitBreakerOpenException> {
            client.request("http://127.0.0.1:1", "GET", "/unavailable")
        }
    }

    @Test
    fun `transport failure becomes dependency error`() {
        assertFailsWith<ApiException> {
            InternalHttpClient("http://127.0.0.1:1", Duration.ofMillis(100)).get("/unavailable")
        }
    }

    private fun testServer(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.createContext("/") { exchange -> handler(exchange) }
        it.start()
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
