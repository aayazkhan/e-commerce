package com.ecommerce.notification

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotificationProviderTest {
    private val delivery = Delivery(
        id = "delivery-1",
        eventId = "event-1",
        userId = "user-1",
        channel = NotificationChannel.EMAIL,
        templateKey = "order.status",
        locale = "en-IN",
        subject = "Order \"updated\"",
        body = "Order body",
        attempts = 0,
        provider = null,
    )

    @Test
    fun `provider rejects missing endpoint before making a request`() {
        val provider = HttpNotificationProvider("email", "", "secret")

        val error = assertFailsWith<IllegalStateException> { provider.send(delivery) }

        assertEquals("email provider is not configured", error.message)
    }

    @Test
    fun `provider returns response message id when provider supplies one`() = withServer(200, "provider-message-1") { endpoint, requests ->
        val provider = HttpNotificationProvider("email", endpoint, "secret")

        val messageId = provider.send(delivery)

        assertEquals("provider-message-1", messageId)
        assertEquals(1, requests())
    }

    @Test
    fun `provider falls back to delivery id when response has no message id`() = withServer(204, null) { endpoint, requests ->
        val provider = HttpNotificationProvider("email", endpoint, "secret")

        val messageId = provider.send(delivery)

        assertEquals(delivery.id, messageId)
        assertEquals(1, requests())
    }

    @Test
    fun `provider exposes non success response as provider failure`() = withServer(503, null) { endpoint, requests ->
        val provider = HttpNotificationProvider("email", endpoint, "secret")

        val error = assertFailsWith<IllegalStateException> { provider.send(delivery) }

        assertEquals("email provider returned 503", error.message)
        assertEquals(1, requests())
    }

    @Test
    fun `provider escapes quotes in both subject and body before sending json`() {
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            HttpNotificationProvider("email", "http://127.0.0.1:${server.address.port}/", "secret")
                .send(delivery.copy(body = "Body \"updated\""))
        } finally {
            server.stop(0)
        }

        assertTrue(requestBody.contains("\\\"updated\\\""))
        assertTrue(requestBody.contains("\"recipient\":\"user-1\""))
    }

    private fun withServer(status: Int, messageId: String?, block: (String, () -> Int) -> Unit) {
        val requests = intArrayOf(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests[0]++
            exchange.requestBody.use { it.readBytes() }
            messageId?.let { exchange.responseHeaders.add("X-Message-Id", it) }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/", { requests[0] })
        } finally {
            server.stop(0)
        }
    }
}
