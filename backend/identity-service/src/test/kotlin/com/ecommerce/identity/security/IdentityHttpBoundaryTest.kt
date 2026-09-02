package com.ecommerce.identity.security

import com.ecommerce.identity.config.ChallengeDeliveryConfig
import com.ecommerce.identity.config.OAuthConfig
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

class IdentityHttpBoundaryTest {
    @Test
    fun `oauth verifier normalizes provider and maps optional profile fields`() {
        val server = server { exchange ->
            val body = if (exchange.requestURI.path == "/google") "{\"sub\":\"google-1\",\"email\":\"User@Example.COM\",\"given_name\":\"User\",\"family_name\":\"Example\"}" else "{\"sub\":\"apple-1\"}"
            exchange.respond(200, body)
        }
        try {
            val verifier = ConfiguredOAuthIdentityVerifier(OAuthConfig("http://localhost:${server.address.port}/google", "http://localhost:${server.address.port}/apple"))
            assertEquals(OAuthIdentity("GOOGLE", "google-1", "user@example.com", "User", "Example"), verifier.verify("google", "credential"))
            assertEquals(OAuthIdentity("APPLE", "apple-1", null, "Customer", ""), verifier.verify("APPLE", "credential"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `oauth verifier maps unsupported endpoint status malformed and missing subject`() {
        val unsupported = ConfiguredOAuthIdentityVerifier(OAuthConfig(null, null))
        assertFailsWith<ApiException> { unsupported.verify("facebook", "credential") }
        assertFailsWith<ApiException> { ConfiguredOAuthIdentityVerifier(OAuthConfig(null, "http://localhost:1/apple")).verify("google", "credential") }
        assertFailsWith<ApiException> { ConfiguredOAuthIdentityVerifier(OAuthConfig("http://localhost:1/google", null)).verify("apple", "credential") }
        assertFailsWith<IllegalArgumentException> { ConfiguredOAuthIdentityVerifier(OAuthConfig("http://127.0.0.1/user", null)).verify("google", "credential") }

        val server = server { exchange -> exchange.respond(if (exchange.requestURI.path == "/status") 401 else 200, if (exchange.requestURI.path == "/malformed") "not-json" else "{}") }
        try {
            val verifier = ConfiguredOAuthIdentityVerifier(OAuthConfig("http://localhost:${server.address.port}/status", "http://localhost:${server.address.port}/malformed"))
            assertFailsWith<ApiException> { verifier.verify("google", "credential") }
            assertFailsWith<ApiException> { verifier.verify("apple", "credential") }
        } finally {
            server.stop(0)
        }

        val blankSubjectServer = server { exchange -> exchange.respond(200, "{\"sub\":\"   \"}") }
        try {
            assertFailsWith<ApiException> {
                ConfiguredOAuthIdentityVerifier(OAuthConfig("http://localhost:${blankSubjectServer.address.port}/userinfo", null))
                    .verify("google", "credential")
            }
        } finally {
            blankSubjectServer.stop(0)
        }
        assertFailsWith<ApiException> {
            ConfiguredOAuthIdentityVerifier(OAuthConfig("http://localhost:1/unavailable", null))
                .verify("google", "credential")
        }
        assertFailsWith<ApiException> {
            ConfiguredOAuthIdentityVerifier(OAuthConfig("https://localhost:1/unavailable", null))
                .verify("google", "credential")
        }

        val boundaryServer = server { exchange ->
            when (exchange.requestURI.path) {
                "/success-200" -> exchange.respond(200, "{\"sub\":\"boundary\",\"email\":\"boundary@example.com\"}")
                "/success-299" -> exchange.respond(299, "{\"sub\":\"boundary\",\"email\":\"boundary@example.com\"}")
                else -> exchange.respond(300, "redirect")
            }
        }
        try {
            val verifier = ConfiguredOAuthIdentityVerifier(OAuthConfig("http://localhost:${boundaryServer.address.port}/success-200", "http://localhost:${boundaryServer.address.port}/success-299"))
            assertEquals("boundary", verifier.verify("google", "credential").subject)
            assertEquals("boundary", verifier.verify("apple", "credential").subject)
            assertFailsWith<ApiException> {
                ConfiguredOAuthIdentityVerifier(OAuthConfig("http://localhost:${boundaryServer.address.port}/redirect", null)).verify("google", "credential")
            }
        } finally {
            boundaryServer.stop(0)
        }
    }

    @Test
    fun `challenge delivery posts otp and verification payloads`() {
        val server = server { exchange -> exchange.respond(204, "") }
        try {
            val delivery = HttpChallengeDelivery(ChallengeDeliveryConfig("http://localhost:${server.address.port}/deliver", "secret"))
            delivery.deliverOtp(null, "+919999999999", OtpPurpose.PHONE_VERIFICATION, "123456")
            delivery.deliverVerification("user-1", "user@example.com", VerificationPurpose.EMAIL_VERIFICATION, "token")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `challenge delivery rejects missing insecure failed and unavailable endpoints`() {
        assertFailsWith<ApiException> { HttpChallengeDelivery(ChallengeDeliveryConfig(null, null)).deliverVerification("user", "user@example.com", VerificationPurpose.EMAIL_VERIFICATION, "token") }
        assertFailsWith<IllegalArgumentException> { HttpChallengeDelivery(ChallengeDeliveryConfig("http://127.0.0.1/deliver", null)).deliverVerification("user", "user@example.com", VerificationPurpose.EMAIL_VERIFICATION, "token") }

        val server = server { exchange -> exchange.respond(500, "failed") }
        try {
            assertFailsWith<ApiException> { HttpChallengeDelivery(ChallengeDeliveryConfig("http://localhost:${server.address.port}/deliver", null)).deliverOtp("user", "user@example.com", OtpPurpose.EMAIL_VERIFICATION, "123456") }
        } finally {
            server.stop(0)
        }
        assertFailsWith<ApiException> { HttpChallengeDelivery(ChallengeDeliveryConfig("http://localhost:1/deliver", null)).deliverVerification("user", "user@example.com", VerificationPurpose.EMAIL_VERIFICATION, "token") }
        assertFailsWith<ApiException> { HttpChallengeDelivery(ChallengeDeliveryConfig("https://localhost:1/deliver", " ")).deliverVerification("user", "user@example.com", VerificationPurpose.EMAIL_VERIFICATION, "token") }

        val boundaryServer = server { exchange -> exchange.respond(299, "") }
        try {
            val delivery = HttpChallengeDelivery(ChallengeDeliveryConfig("http://localhost:${boundaryServer.address.port}/ok", " "))
            delivery.deliverVerification("user", "user@example.com", VerificationPurpose.EMAIL_VERIFICATION, "token")
        } finally {
            boundaryServer.stop(0)
        }
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).also {
        it.createContext("/") { exchange -> handler(exchange) }
        it.start()
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
