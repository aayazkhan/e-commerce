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

class SmsProviderTest {
    @Test
    fun `msg91 provider posts the otp with the configured template and authkey`() {
        var capturedQuery: String? = null
        val server = server { exchange ->
            capturedQuery = exchange.requestURI.query
            exchange.respond(200, "{\"type\":\"success\",\"message\":\"req-1\"}")
        }
        try {
            val provider = Msg91SmsProvider("auth-key-1", "template-1", "http://127.0.0.1:${server.address.port}")
            provider.sendOtp("+919999999999", "123456")
            val params = capturedQuery!!.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
            assertEquals("template-1", params["template_id"])
            assertEquals("auth-key-1", params["authkey"])
            assertEquals("123456", params["otp"])
            assertTrue(params["mobile"]!!.contains("919999999999"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `msg91 provider maps a non-success response to a retryable dependency error`() {
        val server = server { it.respond(200, "{\"type\":\"error\",\"message\":\"invalid template\"}") }
        try {
            val provider = Msg91SmsProvider("auth-key-1", "template-1", "http://127.0.0.1:${server.address.port}")
            val error = assertFailsWith<ApiException> { provider.sendOtp("+919999999999", "123456") }
            assertTrue(error.retryable)
            assertEquals(503, error.statusCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `multi-channel delivery prefers the configured sms provider over the local fallback`() {
        val smsSent = mutableListOf<Pair<String, String>>()
        val fallbackCalls = mutableListOf<String>()
        val email = object : EmailProvider {
            override fun send(to: String, subject: String, html: String) = error("unused")
        }
        val sms = object : SmsProvider {
            override fun sendOtp(mobile: String, otp: String) { smsSent += mobile to otp }
        }
        val smsFallback = object : ChallengeDelivery {
            override fun deliverOtp(userId: String?, destination: String, purpose: OtpPurpose, code: String) { fallbackCalls += destination }
            override fun deliverVerification(userId: String, destination: String, purpose: VerificationPurpose, token: String) = error("unused")
        }
        val delivery = MultiChannelChallengeDelivery(email, smsFallback, sms)

        delivery.deliverOtp("user-1", "+919999999999", OtpPurpose.PHONE_VERIFICATION, "654321")

        assertEquals(1, smsSent.size)
        assertEquals("+919999999999" to "654321", smsSent.single())
        assertTrue(fallbackCalls.isEmpty())
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
