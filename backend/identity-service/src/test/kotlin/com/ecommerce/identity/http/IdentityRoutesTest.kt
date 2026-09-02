package com.ecommerce.identity.http

import com.ecommerce.identity.application.IdentityService
import com.ecommerce.identity.config.JwtConfig
import com.ecommerce.identity.config.SecurityConfig
import com.ecommerce.identity.domain.AddressLabel
import com.ecommerce.identity.domain.OtpPurpose
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserAddress
import com.ecommerce.identity.domain.UserProfile
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.identity.domain.VerificationPurpose
import com.ecommerce.identity.infrastructure.AddressInput
import com.ecommerce.identity.infrastructure.CredentialRecord
import com.ecommerce.identity.infrastructure.IdentityGateway
import com.ecommerce.identity.infrastructure.OtpVerification
import com.ecommerce.identity.infrastructure.ProfileUpdate
import com.ecommerce.identity.infrastructure.RegistrationRecord
import com.ecommerce.identity.infrastructure.RotationRecord
import com.ecommerce.identity.infrastructure.SessionRecord
import com.ecommerce.identity.security.ChallengeDelivery
import com.ecommerce.identity.security.JwtService
import com.ecommerce.identity.security.OAuthIdentity
import com.ecommerce.identity.security.OAuthIdentityVerifier
import com.ecommerce.identity.security.PasswordHasher
import com.ecommerce.identity.security.RateLimiter
import com.ecommerce.identity.observability.IdentityMetrics
import com.ecommerce.platform.common.RequestMetadata
import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.patch
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityRoutesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `protected routes reject missing and malformed authentication and normal users are forbidden`() = testApplication {
        val fixture = fixture()
        application { installRoutes(fixture) }

        val missing = client.get("/api/v1/users/me")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertTrue(missing.bodyAsText().contains("AUTHENTICATION_REQUIRED"))

        val malformed = client.get("/api/v1/users/me") { header(HttpHeaders.Authorization, "Bearer malformed") }
        assertEquals(HttpStatusCode.Unauthorized, malformed.status)

        val token = fixture.jwt.issueAccessToken(fixture.account)
        val forbidden = client.get("/api/v1/admin/users") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertTrue(forbidden.bodyAsText().contains("FORBIDDEN"))
    }

    @Test
    fun `authenticated profile and address routes return mapped responses`() = testApplication {
        val fixture = fixture()
        application { installRoutes(fixture) }
        val token = fixture.jwt.issueAccessToken(fixture.account)
        val auth = { request: io.ktor.client.request.HttpRequestBuilder -> request.header(HttpHeaders.Authorization, "Bearer $token") }

        val account = client.get("/api/v1/users/me") { auth(this) }
        assertEquals(HttpStatusCode.OK, account.status)
        assertTrue(account.bodyAsText().contains("user-1"))

        val profile = client.get("/api/v1/profile") { auth(this) }
        assertEquals(HttpStatusCode.OK, profile.status)
        assertTrue(profile.bodyAsText().contains("Ada"))

        val addresses = client.get("/api/v1/addresses") { auth(this) }
        assertEquals(HttpStatusCode.OK, addresses.status)
        assertTrue(addresses.bodyAsText().contains("address-1"))

        val updated = client.patch("/api/v1/profile") {
            auth(this)
            contentType(ContentType.Application.Json)
            setBody("{\"firstName\":\"Grace\",\"marketingEmail\":true}")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertTrue(updated.bodyAsText().contains("Ada"))
    }

    @Test
    fun `admin routes enforce permission parse status and list users`() = testApplication {
        val fixture = fixture(account(roles = setOf("ADMIN"), permissions = setOf("ADMIN_USER_READ", "ADMIN_USER_UPDATE")))
        application { installRoutes(fixture) }
        val token = fixture.jwt.issueAccessToken(fixture.account)
        val auth = { request: io.ktor.client.request.HttpRequestBuilder -> request.header(HttpHeaders.Authorization, "Bearer $token") }

        val users = client.get("/api/v1/admin/users?limit=2") { auth(this) }
        assertEquals(HttpStatusCode.OK, users.status)
        assertTrue(users.bodyAsText().contains("user-1"))

        val invalidStatus = client.patch("/api/v1/admin/users/user-1/status") {
            auth(this)
            contentType(ContentType.Application.Json)
            setBody("{\"status\":\"UNKNOWN\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidStatus.status)
        assertTrue(invalidStatus.bodyAsText().contains("VALIDATION_ERROR"))

        val validStatus = client.patch("/api/v1/admin/users/user-1/status") {
            auth(this)
            contentType(ContentType.Application.Json)
            setBody("{\"status\":\"SUSPENDED\"}")
        }
        assertEquals(HttpStatusCode.OK, validStatus.status)
        assertTrue(validStatus.bodyAsText().contains("SUSPENDED"))
    }

    @Test
    fun `public auth routes map requests and service responses`() = testApplication {
        val fixture = fixture()
        application { installRoutes(fixture) }

        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", "127.0.0.1, 10.0.0.1")
            setBody("{\"identifier\":\"user@example.com\",\"password\":\"Strong-password-123!\"}")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.bodyAsText().contains("accessToken"))

        val otp = client.post("/api/v1/auth/otp/request") {
            contentType(ContentType.Application.Json)
            setBody("{\"destination\":\"user@example.com\",\"purpose\":\"LOGIN\"}")
        }
        assertEquals(HttpStatusCode.OK, otp.status)
        assertTrue(otp.bodyAsText().contains("otp_"))

        val reset = client.post("/api/v1/auth/password/reset") {
            contentType(ContentType.Application.Json)
            setBody("{\"token\":\"reset-token\",\"password\":\"Strong-password-123!\"}")
        }
        assertEquals(HttpStatusCode.OK, reset.status)
        assertTrue(reset.bodyAsText().contains("successfully"))
    }

    @Test
    fun `identity routes cover sessions verification oauth phone addresses account lifecycle and admin actions`() = testApplication {
        val fixture = fixture(account(roles = setOf("ADMIN"), permissions = setOf("ADMIN_USER_READ", "ADMIN_USER_UPDATE")))
        application { installRoutes(fixture) }
        val token = fixture.jwt.issueAccessToken(fixture.account)
        fun io.ktor.client.request.HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $token")
        val register = client.post("/api/v1/auth/register") { contentType(ContentType.Application.Json); setBody("{\"email\":\"new@example.com\",\"password\":\"Strong-password-123!\",\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}") }
        assertEquals(HttpStatusCode.Created, register.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody("{\"refreshToken\":\"refresh-token\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/oauth/google") { contentType(ContentType.Application.Json); setBody("{\"credential\":\"credential\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/logout") { auth(); contentType(ContentType.Application.Json); setBody("{\"sessionId\":\"session-1\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/sessions") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/auth/sessions/session-1") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/auth/sessions") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/email/verify") { contentType(ContentType.Application.Json); setBody("{\"token\":\"verification\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/email/resend") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/password/forgot") { contentType(ContentType.Application.Json); setBody("{\"identifier\":\"user@example.com\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/otp/verify") { contentType(ContentType.Application.Json); setBody("{\"challengeId\":\"challenge-1\",\"code\":\"123456\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/phone/resend") { auth(); contentType(ContentType.Application.Json); setBody("{\"destination\":\"+919999999999\",\"purpose\":\"PHONE_VERIFICATION\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/phone/verify") { auth(); contentType(ContentType.Application.Json); setBody("{\"challengeId\":\"challenge-1\",\"code\":\"123456\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/users/me") { auth(); contentType(ContentType.Application.Json); setBody("{\"lastName\":\"Byron\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/users/me") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/profile") { auth(); contentType(ContentType.Application.Json); setBody("{\"preferredCurrency\":\"USD\"}") }.status)
        val address = "{\"label\":\"HOME\",\"recipientName\":\"Ada\",\"phone\":\"+919999999999\",\"line1\":\"Line 1\",\"city\":\"Pune\",\"state\":\"MH\",\"postalCode\":\"411001\",\"country\":\"IN\"}"
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/addresses") { auth(); contentType(ContentType.Application.Json); setBody(address) }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/addresses/address-1") { auth(); contentType(ContentType.Application.Json); setBody(address) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/addresses/address-1/default") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/addresses/address-1") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/users/user-1") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/users/user-1/suspend") { auth() }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/users/user-1/restore") { auth() }.status)
    }

    @Test
    fun `identity route branches cover optional forwarding headers otp purpose and admin fallbacks`() = testApplication {
        val fixture = fixture(account(roles = setOf("ADMIN"), permissions = emptySet()), otpPurpose = OtpPurpose.PHONE_VERIFICATION.name)
        application { installRoutes(fixture) }
        val token = fixture.jwt.issueAccessToken(fixture.account)
        fun io.ktor.client.request.HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $token")

        val loginWithoutForwardedFor = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.XRequestId, "request-login")
            header("traceparent", "00-trace-login-span-login-01")
            setBody("{\"identifier\":\"user@example.com\",\"password\":\"Strong-password-123!\"}")
        }
        assertEquals(HttpStatusCode.OK, loginWithoutForwardedFor.status)

        val refreshWithForwardedFor = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", "192.0.2.10, 10.0.0.1")
            setBody("{\"refreshToken\":\"refresh-token\"}")
        }
        assertEquals(HttpStatusCode.OK, refreshWithForwardedFor.status)

        val phoneVerification = client.post("/api/v1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("{\"challengeId\":\"challenge-1\",\"code\":\"123456\"}")
        }
        assertEquals(HttpStatusCode.OK, phoneVerification.status)
        assertTrue(!phoneVerification.bodyAsText().contains("auth"))

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/users") {
            auth()
            header(HttpHeaders.XRequestId, "request-123")
            header("traceparent", "00-trace-1-span-1-01")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/users?limit=not-a-number") { auth() }.status)

    }

    @Test
    fun `admin permission allows a non-admin role when the required permission is present`() = testApplication {
        val fixture = fixture(account(roles = setOf("CUSTOMER"), permissions = setOf("ADMIN_USER_READ")))
        application { installRoutes(fixture) }
        val token = fixture.jwt.issueAccessToken(fixture.account)

        val response = client.get("/api/v1/admin/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("user-1"))
    }

    @Test
    fun `protected identity routes reject requests without authentication before reading resources`() = testApplication {
        val fixture = fixture()
        application { installRoutes(fixture) }

        val responses = listOf(
            client.post("/api/v1/auth/logout"),
            client.get("/api/v1/auth/sessions"),
            client.delete("/api/v1/auth/sessions/session-1"),
            client.delete("/api/v1/auth/sessions"),
            client.post("/api/v1/auth/email/resend"),
            client.post("/api/v1/auth/phone/resend"),
            client.post("/api/v1/auth/phone/verify"),
            client.get("/api/v1/users/me"),
            client.patch("/api/v1/users/me"),
            client.delete("/api/v1/users/me"),
            client.get("/api/v1/profile"),
            client.patch("/api/v1/profile"),
            client.get("/api/v1/addresses"),
            client.post("/api/v1/addresses"),
            client.patch("/api/v1/addresses/address-1"),
            client.delete("/api/v1/addresses/address-1"),
            client.post("/api/v1/addresses/address-1/default"),
            client.get("/api/v1/admin/users"),
            client.get("/api/v1/admin/users/user-1"),
            client.patch("/api/v1/admin/users/user-1/status"),
            client.post("/api/v1/admin/users/user-1/suspend"),
            client.post("/api/v1/admin/users/user-1/restore"),
        )

        assertTrue(responses.all { it.status == HttpStatusCode.Unauthorized })
        responses.forEach { it.bodyAsText().also { body -> assertTrue(body.contains("AUTHENTICATION_REQUIRED")) } }
    }

    private fun io.ktor.server.application.Application.installRoutes(fixture: Fixture) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(com.ecommerce.platform.error.ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { identityRoutes(fixture.service, fixture.jwt) }
    }

    private fun fixture(account: UserAccount = account(), otpPurpose: String = OtpPurpose.LOGIN.name): Fixture {
        val gateway = FakeGateway(account)
        gateway.otpPurpose = otpPurpose
        val jwt = JwtService(JwtConfig("issuer", "audience", "v1", mapOf("v1" to "secret"), 900, 30))
        val service = IdentityService(gateway, PasswordHasher(), jwt, NoRateLimiter, NoDelivery, NoOAuth, com.ecommerce.identity.config.SecurityConfig(3, 10, 300, 3, 60), 30, IdentityMetrics())
        return Fixture(service, jwt, gateway, account)
    }

    private data class Fixture(val service: IdentityService, val jwt: JwtService, val gateway: FakeGateway, val account: UserAccount)

    private object NoRateLimiter : RateLimiter { override fun check(key: String, limit: Long, windowSeconds: Long) = Unit }
    private object NoDelivery : ChallengeDelivery {
        override fun deliverOtp(userId: String?, destination: String, purpose: OtpPurpose, code: String) = Unit
        override fun deliverVerification(userId: String, destination: String, purpose: VerificationPurpose, token: String) = Unit
    }
    private object NoOAuth : OAuthIdentityVerifier { override fun verify(provider: String, credential: String) = OAuthIdentity("GOOGLE", "subject", "user@example.com", "Ada", "Lovelace") }

    private class FakeGateway(private var accountValue: UserAccount) : IdentityGateway {
        var otpPurpose: String = OtpPurpose.LOGIN.name
        private val hasher = PasswordHasher()
        private val profileValue = UserProfile("user-1", "Ada", "Lovelace", null, null, null, "en", "INR", false, false, false)
        private val addressValue = UserAddress("address-1", "user-1", AddressLabel.HOME, "Ada", "+919999999999", "Line 1", null, "Pune", "MH", "411001", "IN", null, null, true)
        private val sessionValue = SessionRecord("session-1", "user-1", "device-1", "ANDROID", "1.0", now, now, now.plusSeconds(3600))
        override fun register(email: String?, phone: String?, passwordHash: String, firstName: String, lastName: String, verificationTokenHash: String, now: Instant, eventPayload: (String) -> String) = RegistrationRecord(accountValue.copy(email = email, phone = phone))
        override fun findCredentials(identifier: String) = CredentialRecord(accountValue, hasher.hash("Strong-password-123!"))
        override fun findAccountByProvider(provider: String, subject: String): UserAccount? = null
        override fun linkProvider(userId: String, provider: String, subject: String, now: Instant) = 1
        override fun createOAuthAccount(identity: OAuthIdentity, passwordHash: String, now: Instant, eventPayload: (String) -> String) = accountValue
        override fun recordLoginFailure(userId: String, maxAttempts: Int, lockMinutes: Long, now: Instant) = Unit
        override fun recordLoginSuccess(userId: String, now: Instant) = accountValue
        override fun createSession(userId: String, tokenFamily: String, refreshTokenHash: String, deviceId: String?, platform: String?, appVersion: String?, ipHash: String?, now: Instant, expiresAt: Instant) = sessionValue
        override fun rotateRefreshToken(refreshTokenHash: String, newRefreshTokenHash: String, newSessionId: String, now: Instant, newExpiresAt: Instant) = RotationRecord(accountValue, newSessionId)
        override fun revokeSession(userId: String, sessionId: String, now: Instant) = 1
        override fun revokeAllSessions(userId: String, now: Instant) = 1
        override fun sessions(userId: String) = listOf(sessionValue)
        override fun createVerificationToken(userId: String, purpose: String, tokenHash: String, now: Instant, expiresAt: Instant) = 1
        override fun consumeVerificationToken(tokenHash: String, purpose: String, now: Instant) = accountValue
        override fun createOtp(id: String, userId: String?, purpose: String, destinationHash: String, codeHash: String, now: Instant, expiresAt: Instant) = 1
        override fun verifyOtp(id: String, codeHash: String, maxAttempts: Int, now: Instant, expectedUserId: String?, expectedPurpose: String?) = OtpVerification("user-1", otpPurpose)
        override fun resetPassword(tokenHash: String, passwordHash: String, now: Instant) = 1
        override fun account(userId: String) = accountValue.takeIf { it.id == userId }
        override fun listAccounts(limit: Int) = listOf(accountValue).take(limit)
        override fun setStatus(userId: String, status: UserStatus, actorId: String, correlationId: String): UserAccount { accountValue = accountValue.copy(status = status); return accountValue }
        override fun updateProfile(userId: String, profile: ProfileUpdate, now: Instant) = profileValue
        override fun getProfile(userId: String) = profileValue
        override fun listAddresses(userId: String) = listOf(addressValue)
        override fun createAddress(userId: String, input: AddressInput, now: Instant) = addressValue
        override fun updateAddress(userId: String, addressId: String, input: AddressInput, now: Instant) = addressValue
        override fun deleteAddress(userId: String, addressId: String, now: Instant) = 1
        override fun setDefaultAddress(userId: String, addressId: String, now: Instant) = addressValue
        override fun deactivate(userId: String, now: Instant) = 1
    }

    private companion object {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        fun account(roles: Set<String> = setOf("CUSTOMER"), permissions: Set<String> = setOf("USER:READ")) = UserAccount("user-1", "user@example.com", "+919999999999", UserStatus.ACTIVE, null, null, roles, permissions)
    }
}
