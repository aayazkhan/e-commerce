package com.ecommerce.identity.application

import com.ecommerce.identity.config.JwtConfig
import com.ecommerce.identity.config.SecurityConfig
import com.ecommerce.identity.domain.AddressLabel
import com.ecommerce.identity.domain.OtpPurpose
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserAddress
import com.ecommerce.identity.domain.UserProfile
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.identity.infrastructure.AddressInput
import com.ecommerce.identity.infrastructure.CredentialRecord
import com.ecommerce.identity.infrastructure.IdentityGateway
import com.ecommerce.identity.infrastructure.OtpVerification
import com.ecommerce.identity.infrastructure.ProfileUpdate
import com.ecommerce.identity.infrastructure.RegistrationRecord
import com.ecommerce.identity.infrastructure.RotationRecord
import com.ecommerce.identity.infrastructure.SessionRecord
import com.ecommerce.identity.security.ChallengeDelivery
import com.ecommerce.identity.security.JwtClaims
import com.ecommerce.identity.security.JwtService
import com.ecommerce.identity.security.OAuthIdentity
import com.ecommerce.identity.security.OAuthIdentityVerifier
import com.ecommerce.identity.security.PasswordHasher
import com.ecommerce.identity.security.RateLimiter
import com.ecommerce.identity.observability.IdentityMetrics
import com.ecommerce.platform.common.RequestMetadata
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityServiceTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val metadata = RequestMetadata("request-1", "trace-1")

    @Test
    fun `registration validates identity fields and delivers email verification`() {
        val fixture = fixture()
        assertError(ErrorCode.VALIDATION_ERROR) { fixture.service.register(RegisterCommand(null, null, validPassword, "A", "B"), metadata) }
        assertError(ErrorCode.VALIDATION_ERROR) { fixture.service.register(RegisterCommand("bad", null, validPassword, "A", "B"), metadata) }
        assertError(ErrorCode.VALIDATION_ERROR) { fixture.service.register(RegisterCommand(null, "123", validPassword, "A", "B"), metadata) }
        assertError(ErrorCode.VALIDATION_ERROR) { fixture.service.register(RegisterCommand("user@example.com", null, "weak", "A", "B"), metadata) }
        assertError(ErrorCode.VALIDATION_ERROR) { fixture.service.register(RegisterCommand("user@example.com", null, validPassword, " ", "B"), metadata) }

        val account = fixture.service.register(RegisterCommand("User@Example.com", null, validPassword, " User ", " Example "), metadata)
        assertEquals("user@example.com", account.email)
        assertEquals(1, fixture.delivery.verifications.size)
        assertEquals(1, fixture.metrics.registrationTotal.get())
        assertEquals("{\"userId\":\"user-1\",\"emailPresent\":true,\"phonePresent\":false}", fixture.gateway.registrationEvents.single())

        val phoneOnly = fixture()
        phoneOnly.service.register(RegisterCommand(null, "+91 9999999999", validPassword, "User", "Phone"), metadata)
        assertTrue(phoneOnly.delivery.verifications.isEmpty())
        assertEquals("{\"userId\":\"user-1\",\"emailPresent\":false,\"phonePresent\":true}", phoneOnly.gateway.registrationEvents.single())

        val bothContacts = fixture()
        bothContacts.service.register(RegisterCommand("both@example.com", "+91 9999999999", validPassword, "User", "Both"), metadata)
        assertEquals("{\"userId\":\"user-1\",\"emailPresent\":true,\"phonePresent\":true}", bothContacts.gateway.registrationEvents.single())
        assertError(ErrorCode.VALIDATION_ERROR) { fixture().service.register(RegisterCommand("user@example.com", "12345678901234567", validPassword, "User", "Phone"), metadata) }
        assertError(ErrorCode.VALIDATION_ERROR) { fixture().service.register(RegisterCommand(null, "1234567", validPassword, "User", "Phone"), metadata) }
        fixture().service.register(RegisterCommand(null, "12345678", validPassword, "User", "Phone"), metadata)
        fixture().service.register(RegisterCommand(null, "1234567890123456", validPassword, "User", "Phone"), metadata)
        assertError(ErrorCode.VALIDATION_ERROR) { fixture().service.register(RegisterCommand("user@example.com", null, validPassword, "User", " "), metadata) }
    }

    @Test
    fun `login covers credentials failures account lifecycle and success`() {
        val wrong = fixture().also { it.gateway.credentials = CredentialRecord(account(), "other-hash") }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { wrong.service.login(LoginCommand(" User@Example.com ", validPassword, null, null, null, null), metadata) }
        assertEquals(1, wrong.gateway.loginFailures)
        assertEquals(1, wrong.metrics.loginFailureTotal.get())

        val missing = fixture().also { it.gateway.credentials = null }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { missing.service.login(LoginCommand("missing@example.com", validPassword, null, null, null, null), metadata) }
        assertEquals(0, missing.gateway.loginFailures)
        assertEquals(1, missing.metrics.loginFailureTotal.get())

        for (status in listOf(UserStatus.SUSPENDED, UserStatus.DEACTIVATED, UserStatus.DELETED, UserStatus.LOCKED)) {
            val blocked = fixture().also { it.gateway.credentials = CredentialRecord(account(status = status), PasswordHasher().hash(validPassword)) }
            assertError(ErrorCode.AUTHENTICATION_REQUIRED) { blocked.service.login(LoginCommand("user@example.com", validPassword, null, null, null, null), metadata) }
        }
        val pending = fixture().also { it.gateway.credentials = CredentialRecord(account(UserStatus.PENDING_VERIFICATION), PasswordHasher().hash(validPassword)) }
        assertError(ErrorCode.FORBIDDEN) { pending.service.login(LoginCommand("user@example.com", validPassword, null, null, null, null), metadata) }

        val success = fixture().also { it.gateway.credentials = CredentialRecord(account(), PasswordHasher().hash(validPassword)) }
        val result = success.service.login(LoginCommand("user@example.com", validPassword, "device", "ANDROID", "1.0", "127.0.0.1"), metadata)
        assertEquals("user-1", result.account.id)
        assertEquals(1, success.gateway.createdSessions)
        assertEquals(1, success.metrics.loginSuccessTotal.get())
    }

    @Test
    fun `refresh and logout expose rotation failures and session operations`() {
        val blank = fixture()
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { blank.service.refresh("", SessionContext(null, null, null, null)) }

        val failed = fixture().also { it.gateway.rotationError = ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "expired", 401) }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { failed.service.refresh("refresh", SessionContext(null, null, null, null)) }
        assertEquals(1, failed.metrics.tokenRefreshFailureTotal.get())

        val success = fixture()
        val refreshed = success.service.refresh("refresh", SessionContext("device", "ANDROID", "1.0", "127.0.0.1"))
        assertEquals("session-2", refreshed.sessionId)
        assertEquals(1, success.metrics.tokenRefreshTotal.get())

        success.service.logout("user-1", null, true)
        success.service.logout("user-1", "session-1", false)
        success.service.logout("user-1", null, false)
        assertEquals(1, success.gateway.revokeAllCalls)
        assertEquals(1, success.gateway.revokeCalls)
        assertEquals(success.gateway.sessions, success.service.sessions("user-1"))
    }

    @Test
    fun `oauth login links existing identities creates accounts and rejects unsafe accounts`() {
        val existing = fixture().also { it.oauth.identity = OAuthIdentity("GOOGLE", "sub", "user@example.com", "User", "One") }
        existing.gateway.providerAccount = account()
        existing.service.oauthLogin("google", "credential", SessionContext(null, null, null, null), metadata)
        assertEquals(0, existing.gateway.linkCalls)

        val link = fixture().also { it.oauth.identity = OAuthIdentity("GOOGLE", "sub", "user@example.com", "User", "One"); it.gateway.providerAccount = null }
        link.service.oauthLogin("google", "credential", SessionContext(null, null, null, null), metadata)
        assertEquals(1, link.gateway.linkCalls)

        val created = fixture().also { it.oauth.identity = OAuthIdentity("GOOGLE", "new", "new@example.com", "New", "User"); it.gateway.credentials = null }
        created.service.oauthLogin("google", "credential", SessionContext(null, null, null, null), metadata)
        assertEquals(1, created.gateway.oauthCreates)
        assertEquals("{\"userId\":\"user-1\",\"provider\":\"GOOGLE\"}", created.gateway.oauthEvents.single())

        val noEmail = fixture().also { it.oauth.identity = OAuthIdentity("APPLE", "no-email", null, "Customer", "") }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { noEmail.service.oauthLogin("apple", "credential", SessionContext(null, null, null, null), metadata) }

        val inactive = fixture().also { it.oauth.identity = OAuthIdentity("GOOGLE", "inactive", "user@example.com", "User", "One"); it.gateway.providerAccount = account(UserStatus.SUSPENDED) }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { inactive.service.oauthLogin("google", "credential", SessionContext(null, null, null, null), metadata) }
    }

    @Test
    fun `verification password reset and OTP flows preserve safe failure behavior`() {
        val fixture = fixture()
        fixture.service.verifyEmail("token")
        assertEquals(1, fixture.gateway.verificationConsumes)

        val missing = fixture().also { it.gateway.accountResult = null }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { missing.service.resendEmailVerification("missing") }
        val noEmail = fixture().also { it.gateway.accountResult = account(email = null) }
        assertError(ErrorCode.VALIDATION_ERROR) { noEmail.service.resendEmailVerification("user-1") }
        val verified = fixture().also { it.gateway.accountResult = account(emailVerified = now) }
        verified.service.resendEmailVerification("user-1")
        assertTrue(verified.delivery.verifications.isEmpty())
        fixture.service.resendEmailVerification("user-1")
        assertEquals(1, fixture.gateway.verificationCreates)

        val unknownReset = fixture().also { it.gateway.credentials = null }
        unknownReset.service.requestPasswordReset("unknown", metadata)
        val reset = fixture()
        reset.service.requestPasswordReset(" USER@EXAMPLE.COM ", metadata)
        assertEquals(1, reset.gateway.verificationCreates)
        assertEquals(1, reset.delivery.verifications.size)
        val phoneOnly = fixture().also { it.gateway.credentials = CredentialRecord(account(email = null), PasswordHasher().hash(validPassword)) }
        phoneOnly.service.requestPasswordReset("user@example.com", metadata)
        assertEquals(1, phoneOnly.gateway.verificationCreates)
        assertTrue(phoneOnly.delivery.verifications.isEmpty())
        assertError(ErrorCode.VALIDATION_ERROR) { reset.service.resetPassword("token", "weak") }
        reset.service.resetPassword("token", validPassword)
        assertEquals(1, reset.gateway.passwordResets)

        val otpInvalid = fixture()
        assertError(ErrorCode.VALIDATION_ERROR) { otpInvalid.service.requestOtp(OtpCommand("user-1", "bad", OtpPurpose.EMAIL_VERIFICATION)) }
        val loginOtp = fixture()
        assertTrue(loginOtp.service.requestOtp(OtpCommand(null, "USER@EXAMPLE.COM", OtpPurpose.LOGIN)).startsWith("otp_"))
        assertEquals(1, loginOtp.delivery.otps.size)
        val missingLogin = fixture().also { it.gateway.credentials = null }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { missingLogin.service.requestOtp(OtpCommand(null, "user@example.com", OtpPurpose.LOGIN)) }
        val phoneOtp = fixture()
        phoneOtp.service.requestOtp(OtpCommand("user-1", "+91 9999999999", OtpPurpose.PHONE_VERIFICATION))
        assertEquals(1, phoneOtp.gateway.otpCreates)
        val emailVerificationOtp = fixture()
        emailVerificationOtp.service.requestOtp(OtpCommand("user-1", "user@example.com", OtpPurpose.EMAIL_VERIFICATION))
        assertEquals(1, emailVerificationOtp.gateway.otpCreates)

        val verification = fixture().service.verifyOtp("otp-1", "123456", "user-1", OtpPurpose.LOGIN)
        assertEquals("user-1", verification.userId)
        val otpFailure = fixture().also { it.gateway.otpError = ApiException(ErrorCode.VALIDATION_ERROR, "invalid", 400) }
        assertError(ErrorCode.VALIDATION_ERROR) { otpFailure.service.verifyOtp("otp-1", "bad") }
        assertEquals(1, otpFailure.metrics.otpVerificationFailureTotal.get())
    }

    @Test
    fun `OTP login claims profiles addresses and admin operations delegate with authorization checks`() {
        val fixture = fixture()
        val session = SessionContext(null, null, null, null)
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { fixture.service.loginWithOtp(OtpVerification(null, "LOGIN"), session, metadata) }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { fixture.service.loginWithOtp(OtpVerification("user-1", "EMAIL_VERIFICATION"), session, metadata) }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { fixture.copyGateway(accountResult = null).service.loginWithOtp(OtpVerification("user-1", "LOGIN"), session, metadata) }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { fixture.copyGateway(accountResult = account(UserStatus.SUSPENDED)).service.loginWithOtp(OtpVerification("user-1", "LOGIN"), session, metadata) }
        val active = fixture()
        assertEquals("user-1", active.service.loginWithOtp(OtpVerification("user-1", "LOGIN"), session, metadata).account.id)

        val claims = JwtClaims("user-1", emptyList(), emptyList(), "token", 1, 2, "issuer", "audience")
        assertEquals("user-1", active.service.accountFromClaims(claims).id)
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { fixture().copyGateway(accountResult = account(UserStatus.DEACTIVATED)).service.accountFromClaims(claims) }
        assertError(ErrorCode.AUTHENTICATION_REQUIRED) { fixture().copyGateway(accountResult = null).service.accountFromClaims(claims) }
        assertEquals("user-1", fixture().copyGateway(accountResult = account(UserStatus.PENDING_VERIFICATION)).service.accountFromClaims(claims).id)

        assertEquals(active.profile, active.service.updateProfile("user-1", ProfileCommand("New", null, "en", "INR", true, false, true)))
        assertEquals(active.profile, active.service.profile("user-1"))
        assertEquals(active.addresses, active.service.addresses("user-1"))
        val address = AddressCommand(AddressLabel.HOME, " Name ", "+91 9999999999", " Street ", " Apt 2 ", "Pune", "MH", "411001", "in", null, null, false)
        assertEquals(active.address, active.service.createAddress("user-1", address))
        assertEquals(active.address, active.service.updateAddress("user-1", "address-1", address))
        active.service.deleteAddress("user-1", "address-1")
        assertEquals(active.address, active.service.setDefaultAddress("user-1", "address-1"))
        active.service.deactivate("user-1")
        assertEquals(1, active.gateway.deactivateCalls)

        assertEquals(active.accounts, active.service.adminUsers(10))
        assertEquals(active.account, active.service.adminUser("user-1"))
        assertError(ErrorCode.NOT_FOUND) { fixture.copyGateway(accountResult = null).service.adminUser("missing") }
        assertEquals(active.account, active.service.adminSetStatus("user-1", UserStatus.ACTIVE, "admin", "corr"))
    }

    @Test
    fun `rate limiter and delivery dependency failures are propagated`() {
        val fixture = fixture().also { it.rate.failure = ApiException(ErrorCode.RATE_LIMITED, "limited", 429) }
        assertError(ErrorCode.RATE_LIMITED) { fixture.service.login(LoginCommand("user@example.com", validPassword, null, null, null, null), metadata) }
        assertEquals(0, fixture.gateway.loginFailures)
    }

    private fun assertError(code: ErrorCode, block: () -> Unit) {
        val error = assertFailsWith<ApiException> { block() }
        assertEquals(code, error.errorCode)
    }

    private fun fixture(): Fixture {
        val gateway = FakeGateway()
        val rate = FakeRateLimiter()
        val delivery = FakeDelivery()
        val oauth = FakeOAuthVerifier()
        val metrics = IdentityMetrics()
        val service = IdentityService(
            gateway,
            PasswordHasher(),
            JwtService(JwtConfig("issuer", "audience", "v1", mapOf("v1" to "secret"), 900, 30)),
            rate,
            delivery,
            oauth,
            SecurityConfig(3, 10, 300, 3, 60),
            30,
            metrics,
        )
        return Fixture(service, gateway, rate, delivery, oauth, metrics)
    }

    private fun Fixture.copyGateway(accountResult: UserAccount?): Fixture {
        gateway.accountResult = accountResult
        return this
    }

    private data class Fixture(
        val service: IdentityService,
        val gateway: FakeGateway,
        val rate: FakeRateLimiter,
        val delivery: FakeDelivery,
        val oauth: FakeOAuthVerifier,
        val metrics: IdentityMetrics,
    ) {
        val account = gateway.accountResult!!
        val profile = gateway.profile
        val address = gateway.address
        val addresses = gateway.addresses
        val accounts = gateway.accounts
    }

    private class FakeRateLimiter : RateLimiter {
        var failure: RuntimeException? = null
        val calls = mutableListOf<String>()
        override fun check(key: String, limit: Long, windowSeconds: Long) {
            calls += key
            failure?.let { throw it }
        }
    }

    private class FakeDelivery : ChallengeDelivery {
        val otps = mutableListOf<String>()
        val verifications = mutableListOf<String>()
        override fun deliverOtp(userId: String?, destination: String, purpose: OtpPurpose, code: String) { otps += "$destination:$purpose" }
        override fun deliverVerification(userId: String, destination: String, purpose: com.ecommerce.identity.domain.VerificationPurpose, token: String) { verifications += "$destination:$purpose" }
    }

    private class FakeOAuthVerifier : OAuthIdentityVerifier {
        var identity = OAuthIdentity("GOOGLE", "subject", "user@example.com", "User", "Example")
        override fun verify(provider: String, credential: String): OAuthIdentity = identity
    }

    private class FakeGateway : IdentityGateway {
        var accountResult: UserAccount? = account()
        var credentials: CredentialRecord? = CredentialRecord(accountResult!!, PasswordHasher().hash(validPassword))
        var providerAccount: UserAccount? = null
        var rotationError: RuntimeException? = null
        var otpError: RuntimeException? = null
        var loginFailures = 0
        var createdSessions = 0
        var revokeCalls = 0
        var revokeAllCalls = 0
        var linkCalls = 0
        var oauthCreates = 0
        val oauthEvents = mutableListOf<String>()
        var verificationConsumes = 0
        var verificationCreates = 0
        var otpCreates = 0
        var passwordResets = 0
        var deactivateCalls = 0
        val sessions = listOf(SessionRecord("session-1", "user-1", null, null, null, Instant.EPOCH, Instant.EPOCH, Instant.MAX))
        val profile = UserProfile("user-1", "User", "Example", null, null, null, "en", "INR", true, false, true)
        val address = UserAddress("address-1", "user-1", AddressLabel.HOME, "Name", "+919999999999", "Street", null, "Pune", "MH", "411001", "IN", null, null, false)
        val addresses = listOf(address)
        val accounts = listOf(account())

        val registrationEvents = mutableListOf<String>()
        override fun register(email: String?, phone: String?, passwordHash: String, firstName: String, lastName: String, verificationTokenHash: String, now: Instant, eventPayload: (String) -> String): RegistrationRecord {
            registrationEvents += eventPayload(accountResult!!.id)
            return RegistrationRecord(accountResult!!)
        }
        override fun findCredentials(identifier: String) = credentials
        override fun findAccountByProvider(provider: String, subject: String) = providerAccount
        override fun linkProvider(userId: String, provider: String, subject: String, now: Instant): Int { linkCalls++; return 1 }
        override fun createOAuthAccount(identity: OAuthIdentity, passwordHash: String, now: Instant, eventPayload: (String) -> String): UserAccount { oauthCreates++; oauthEvents += eventPayload(accountResult!!.id); return accountResult!! }
        override fun recordLoginFailure(userId: String, maxAttempts: Int, lockMinutes: Long, now: Instant): Any { loginFailures++; return Unit }
        override fun recordLoginSuccess(userId: String, now: Instant) = accountResult!!
        override fun createSession(userId: String, tokenFamily: String, refreshTokenHash: String, deviceId: String?, platform: String?, appVersion: String?, ipHash: String?, now: Instant, expiresAt: Instant): SessionRecord { createdSessions++; return SessionRecord("session-1", userId, deviceId, platform, appVersion, now, now, expiresAt) }
        override fun rotateRefreshToken(refreshTokenHash: String, newRefreshTokenHash: String, newSessionId: String, now: Instant, newExpiresAt: Instant): RotationRecord { rotationError?.let { throw it }; return RotationRecord(accountResult!!, "session-2") }
        override fun revokeSession(userId: String, sessionId: String, now: Instant): Int { revokeCalls++; return 1 }
        override fun revokeAllSessions(userId: String, now: Instant): Int { revokeAllCalls++; return 1 }
        override fun sessions(userId: String) = sessions
        override fun createVerificationToken(userId: String, purpose: String, tokenHash: String, now: Instant, expiresAt: Instant): Int { verificationCreates++; return 1 }
        override fun consumeVerificationToken(tokenHash: String, purpose: String, now: Instant): UserAccount { verificationConsumes++; return accountResult!! }
        override fun createOtp(id: String, userId: String?, purpose: String, destinationHash: String, codeHash: String, now: Instant, expiresAt: Instant): Int { otpCreates++; return 1 }
        override fun verifyOtp(id: String, codeHash: String, maxAttempts: Int, now: Instant, expectedUserId: String?, expectedPurpose: String?): OtpVerification { otpError?.let { throw it }; return OtpVerification("user-1", "LOGIN") }
        override fun resetPassword(tokenHash: String, passwordHash: String, now: Instant): Int { passwordResets++; return 1 }
        override fun account(userId: String) = accountResult
        override fun listAccounts(limit: Int) = accounts
        override fun setStatus(userId: String, status: UserStatus, actorId: String, correlationId: String) = accountResult!!
        override fun updateProfile(userId: String, profile: ProfileUpdate, now: Instant) = this.profile
        override fun getProfile(userId: String) = profile
        override fun listAddresses(userId: String) = addresses
        override fun createAddress(userId: String, input: AddressInput, now: Instant) = address
        override fun updateAddress(userId: String, addressId: String, input: AddressInput, now: Instant) = address
        override fun deleteAddress(userId: String, addressId: String, now: Instant): Int = 1
        override fun setDefaultAddress(userId: String, addressId: String, now: Instant) = address
        override fun deactivate(userId: String, now: Instant): Int { deactivateCalls++; return 1 }
        override fun grantRole(userId: String, role: String, now: Instant) = accountResult!!
    }

    private companion object {
        const val validPassword = "Strong-password-123!"

        fun account(status: UserStatus = UserStatus.ACTIVE, email: String? = "user@example.com", emailVerified: Instant? = null) =
            UserAccount("user-1", email, "+919999999999", status, emailVerified, null, setOf("CUSTOMER"), setOf("USER:READ"))
    }
}
