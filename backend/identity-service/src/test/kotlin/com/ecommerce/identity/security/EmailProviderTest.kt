package com.ecommerce.identity.security

import com.ecommerce.identity.domain.OtpPurpose
import com.ecommerce.identity.domain.VerificationPurpose
import com.ecommerce.platform.error.ApiException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmailProviderTest {
    @Test
    fun `resend provider posts the expected request and rejects non-success responses`() {
        var capturedAuth: String? = null
        var capturedBody: String? = null
        val server = server { exchange ->
            capturedAuth = exchange.requestHeaders.getFirst("Authorization")
            capturedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            exchange.respond(200, "{\"id\":\"email-1\"}")
        }
        try {
            val provider = ResendEmailProvider("re_test_key", "sender@example.com", "http://127.0.0.1:${server.address.port}")
            provider.send("user@example.com", "Your code", "<p>123456</p>")
            assertEquals("Bearer re_test_key", capturedAuth)
            assertTrue(capturedBody!!.contains("\"to\":[\"user@example.com\"]"))
            assertTrue(capturedBody!!.contains("\"subject\":\"Your code\""))
            assertTrue(capturedBody!!.contains("sender@example.com"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `resend provider maps a rejected send to a retryable dependency error`() {
        val server = server { it.respond(422, "{\"message\":\"invalid\"}") }
        try {
            val provider = ResendEmailProvider("re_test_key", "sender@example.com", "http://127.0.0.1:${server.address.port}")
            val error = assertFailsWith<ApiException> { provider.send("user@example.com", "subject", "body") }
            assertTrue(error.retryable)
            assertEquals(503, error.statusCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `multi-channel delivery sends email destinations through the email provider and phone destinations through the sms fallback`() {
        val sentEmails = mutableListOf<Triple<String, String, String>>()
        val smsCalls = mutableListOf<String>()
        val email = object : EmailProvider {
            override fun send(to: String, subject: String, html: String) { sentEmails += Triple(to, subject, html) }
        }
        val smsFallback = object : ChallengeDelivery {
            override fun deliverOtp(userId: String?, destination: String, purpose: OtpPurpose, code: String) { smsCalls += destination }
            override fun deliverVerification(userId: String, destination: String, purpose: VerificationPurpose, token: String) = error("unused")
        }
        val delivery = MultiChannelChallengeDelivery(email, smsFallback)

        delivery.deliverOtp("user-1", "person@example.com", OtpPurpose.LOGIN, "123456")
        delivery.deliverOtp("user-1", "+919999999999", OtpPurpose.PHONE_VERIFICATION, "654321")
        delivery.deliverVerification("user-1", "person@example.com", VerificationPurpose.EMAIL_VERIFICATION, "verify-token")

        assertEquals(1, smsCalls.size)
        assertEquals("+919999999999", smsCalls.single())
        assertEquals(2, sentEmails.size)
        assertTrue(sentEmails[0].third.contains("123456"))
        assertTrue(sentEmails[1].third.contains("verify-token"))
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.createContext("/") { exchange -> handler(exchange) }
        it.start()
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
