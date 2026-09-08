package com.ecommerce.identity.application

import com.ecommerce.identity.config.SecurityConfig
import com.ecommerce.identity.domain.AddressLabel
import com.ecommerce.identity.domain.OtpPurpose
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserAddress
import com.ecommerce.identity.domain.UserProfile
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.identity.domain.normalizeEmail
import com.ecommerce.identity.domain.normalizePhone
import com.ecommerce.identity.domain.validateEmail
import com.ecommerce.identity.domain.validatePassword
import com.ecommerce.identity.infrastructure.AddressInput
import com.ecommerce.identity.infrastructure.IdentityGateway
import com.ecommerce.identity.infrastructure.OtpVerification
import com.ecommerce.identity.infrastructure.ProfileUpdate
import com.ecommerce.identity.infrastructure.SessionRecord
import com.ecommerce.identity.security.ChallengeDelivery
import com.ecommerce.identity.security.JwtClaims
import com.ecommerce.identity.security.JwtService
import com.ecommerce.identity.security.PasswordHasher
import com.ecommerce.identity.security.OAuthIdentityVerifier
import com.ecommerce.identity.security.RateLimiter
import com.ecommerce.identity.security.sha256Hex
import com.ecommerce.identity.observability.IdentityMetrics
import com.ecommerce.platform.common.RequestMetadata
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.security.SecureRandom
import java.time.Instant

class IdentityService(
    private val repository: IdentityGateway,
    private val passwordHasher: PasswordHasher,
    private val jwtService: JwtService,
    private val rateLimiter: RateLimiter,
    private val delivery: ChallengeDelivery,
    private val oauthVerifier: OAuthIdentityVerifier,
    private val security: SecurityConfig,
    private val refreshTokenDays: Long,
    private val metrics: IdentityMetrics,
) {
    private val random = SecureRandom()

    fun register(input: RegisterCommand, metadata: RequestMetadata): UserAccount {
        val email = input.email?.let(::normalizeEmail)
        val phone = input.phone?.let(::normalizePhone)
        if (email == null && phone == null) validation("Email or phone is required")
        if (email != null && !validateEmail(email)) validation("Email is invalid")
        if (phone != null && phone.length !in 8..16) validation("Phone is invalid")
        val passwordErrors = validatePassword(input.password)
        if (passwordErrors.isNotEmpty()) validation(passwordErrors.joinToString("; "))
        if (input.firstName.isBlank() || input.lastName.isBlank()) validation("Name is required")

        val verificationToken = randomToken(32)
        val account = repository.register(
            email = email,
            phone = phone,
            passwordHash = passwordHasher.hash(input.password),
            firstName = input.firstName.trim(),
            lastName = input.lastName.trim(),
            verificationTokenHash = sha256Hex(verificationToken),
            now = Instant.now(),
            eventPayload = { userId -> "{\"userId\":\"$userId\",\"emailPresent\":${email != null},\"phonePresent\":${phone != null}}" },
        ).account
        if (email != null) delivery.deliverVerification(account.id, email, com.ecommerce.identity.domain.VerificationPurpose.EMAIL_VERIFICATION, verificationToken)
        metrics.registrationTotal.incrementAndGet()
        return account
    }

    fun login(input: LoginCommand, metadata: RequestMetadata): AuthResult {
        val identifier = input.identifier.trim().lowercase()
        rateLimiter.check("auth:ratelimit:login:${sha256Hex(identifier)}", 20, 60)
        val credentials = repository.findCredentials(identifier)
        if (credentials == null || !passwordHasher.verify(credentials.passwordHash, input.password)) {
            metrics.loginFailureTotal.incrementAndGet()
            credentials?.let { repository.recordLoginFailure(it.account.id, security.maxLoginAttempts, security.lockMinutes, Instant.now()) }
            throw authFailure()
        }
        if (credentials.account.status == UserStatus.SUSPENDED || credentials.account.status == UserStatus.DEACTIVATED || credentials.account.status == UserStatus.DELETED) throw authFailure()
        if (credentials.account.status == UserStatus.LOCKED) throw authFailure()
        if (credentials.account.status == UserStatus.PENDING_VERIFICATION) {
            throw ApiException(ErrorCode.FORBIDDEN, "Account verification is required.", 403)
        }
        val account = repository.recordLoginSuccess(credentials.account.id, Instant.now())
        metrics.loginSuccessTotal.incrementAndGet()
        return issueSession(account, input.deviceId, input.platform, input.appVersion, input.ipAddress, metadata)
    }

    fun refresh(rawRefreshToken: String, input: SessionContext): AuthResult {
        if (rawRefreshToken.isBlank()) throw authFailure()
        val now = Instant.now()
        val newRefreshToken = jwtService.newRefreshToken()
        val newSessionId = "ses_${randomToken(18)}"
        val rotation = try { repository.rotateRefreshToken(
            refreshTokenHash = sha256Hex(rawRefreshToken),
            newRefreshTokenHash = sha256Hex(newRefreshToken),
            newSessionId = newSessionId,
            now = now,
            newExpiresAt = now.plusSeconds(refreshTokenDays * 86_400),
        ) } catch (error: Exception) {
            metrics.tokenRefreshFailureTotal.incrementAndGet()
            throw error
        }
        metrics.tokenRefreshTotal.incrementAndGet()
        return AuthResult(
            accessToken = jwtService.issueAccessToken(rotation.account, now),
            refreshToken = newRefreshToken,
            expiresIn = 900,
            sessionId = rotation.sessionId,
            account = rotation.account,
        )
    }

    fun oauthLogin(provider: String, credential: String, session: SessionContext, metadata: RequestMetadata): AuthResult {
        val identity = oauthVerifier.verify(provider, credential)
        val now = Instant.now()
        val account = repository.findAccountByProvider(identity.provider, identity.subject)
            ?: identity.email?.let { repository.findCredentials(normalizeEmail(it))?.account }?.also {
                repository.linkProvider(it.id, identity.provider, identity.subject, now)
            }
            ?: run {
                val email = identity.email?.let(::normalizeEmail) ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "OAuth provider did not return a verified email.", 401)
                repository.createOAuthAccount(identity.copy(email = email), passwordHasher.hash(randomToken(32)), now) { userId -> "{\"userId\":\"$userId\",\"provider\":\"${identity.provider}\"}" }
            }
        if (account.status != UserStatus.ACTIVE) throw authFailure()
        return issueSession(account, session.deviceId, session.platform, session.appVersion, session.ipAddress, metadata)
    }

    fun logout(userId: String, sessionId: String?, allSessions: Boolean) {
        val now = Instant.now()
        if (allSessions) repository.revokeAllSessions(userId, now) else if (sessionId != null) repository.revokeSession(userId, sessionId, now)
    }

    fun sessions(userId: String): List<SessionRecord> = repository.sessions(userId)

    fun verifyEmail(token: String): UserAccount = repository.consumeVerificationToken(sha256Hex(token), "EMAIL_VERIFICATION", Instant.now())

    fun resendEmailVerification(userId: String) {
        val account = repository.account(userId) ?: throw authFailure()
        val email = account.email ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "No email is associated with this account.", 400)
        if (account.emailVerifiedAt != null) return
        val token = randomToken(32)
        val now = Instant.now()
        repository.createVerificationToken(userId, "EMAIL_VERIFICATION", sha256Hex(token), now, now.plusSeconds(86_400))
        delivery.deliverVerification(userId, email, com.ecommerce.identity.domain.VerificationPurpose.EMAIL_VERIFICATION, token)
    }

    fun requestPasswordReset(identifier: String, metadata: RequestMetadata) {
        val normalized = identifier.trim().lowercase()
        rateLimiter.check("auth:ratelimit:password-reset:${sha256Hex(normalized)}", 5, 900)
        val credentials = repository.findCredentials(normalized) ?: return
        val token = randomToken(32)
        val now = Instant.now()
        repository.createVerificationToken(credentials.account.id, "PASSWORD_RESET", sha256Hex(token), now, now.plusSeconds(900))
        credentials.account.email?.let { delivery.deliverVerification(credentials.account.id, it, com.ecommerce.identity.domain.VerificationPurpose.PASSWORD_RESET, token) }
    }

    fun resetPassword(token: String, password: String) {
        val errors = validatePassword(password)
        if (errors.isNotEmpty()) validation(errors.joinToString("; "))
        repository.resetPassword(sha256Hex(token), passwordHasher.hash(password), Instant.now())
        metrics.passwordResetTotal.incrementAndGet()
    }

    fun requestOtp(input: OtpCommand): String {
        val destination = if (input.purpose == OtpPurpose.PHONE_VERIFICATION) normalizePhone(input.destination) else normalizeEmail(input.destination)
        if (input.purpose != OtpPurpose.PHONE_VERIFICATION && !validateEmail(destination)) validation("Email is invalid")
        rateLimiter.check("auth:ratelimit:otp:${input.purpose}:${sha256Hex(destination)}", 3, security.otpResendSeconds)
        val resolvedUserId = if (input.purpose == OtpPurpose.LOGIN) {
            repository.findCredentials(destination)?.account?.id ?: throw authFailure()
        } else input.userId
        val code = (100_000 + random.nextInt(900_000)).toString()
        val id = "otp_${randomToken(18)}"
        val now = Instant.now()
        repository.createOtp(id, resolvedUserId, input.purpose.name, sha256Hex(destination), sha256Hex(code), now, now.plusSeconds(security.otpExpirySeconds))
        delivery.deliverOtp(resolvedUserId, destination, input.purpose, code)
        metrics.otpRequestsTotal.incrementAndGet()
        return id
    }

    fun verifyOtp(challengeId: String, code: String, expectedUserId: String? = null, expectedPurpose: OtpPurpose? = null): OtpVerification = try { repository.verifyOtp(challengeId, sha256Hex(code), security.otpMaxAttempts, Instant.now(), expectedUserId, expectedPurpose?.name) } catch (error: Exception) { metrics.otpVerificationFailureTotal.incrementAndGet(); throw error }

    fun loginWithOtp(verification: OtpVerification, session: SessionContext, metadata: RequestMetadata): AuthResult {
        if (verification.purpose != OtpPurpose.LOGIN.name || verification.userId == null) throw authFailure()
        val account = repository.account(verification.userId) ?: throw authFailure()
        if (account.status != UserStatus.ACTIVE) throw authFailure()
        return issueSession(account, session.deviceId, session.platform, session.appVersion, session.ipAddress, metadata)
    }

    fun accountFromClaims(claims: JwtClaims): UserAccount = repository.account(claims.subject)?.also {
        if (it.status !in setOf(UserStatus.ACTIVE, UserStatus.PENDING_VERIFICATION)) throw authFailure()
    } ?: throw authFailure()

    fun updateProfile(userId: String, input: ProfileCommand): UserProfile = repository.updateProfile(
        userId,
        ProfileUpdate(input.firstName, input.lastName, input.preferredLanguage, input.preferredCurrency, input.marketingEmail, input.marketingSms, input.marketingPush),
        Instant.now(),
    )

    fun profile(userId: String): UserProfile = repository.getProfile(userId)
    fun addresses(userId: String): List<UserAddress> = repository.listAddresses(userId)
    fun createAddress(userId: String, input: AddressCommand): UserAddress = repository.createAddress(userId, input.toRepository(), Instant.now())
    fun updateAddress(userId: String, addressId: String, input: AddressCommand): UserAddress = repository.updateAddress(userId, addressId, input.toRepository(), Instant.now())
    fun deleteAddress(userId: String, addressId: String) = repository.deleteAddress(userId, addressId, Instant.now())
    fun setDefaultAddress(userId: String, addressId: String): UserAddress = repository.setDefaultAddress(userId, addressId, Instant.now())
    fun deactivate(userId: String) = repository.deactivate(userId, Instant.now())

    private fun issueSession(account: UserAccount, deviceId: String?, platform: String?, appVersion: String?, ip: String?, metadata: RequestMetadata): AuthResult {
        val refreshToken = jwtService.newRefreshToken()
        val now = Instant.now()
        val session = repository.createSession(account.id, "fam_${randomToken(18)}", sha256Hex(refreshToken), deviceId, platform, appVersion, ip?.let(::sha256Hex), now, now.plusSeconds(refreshTokenDays * 86_400))
        return AuthResult(jwtService.issueAccessToken(account, now), refreshToken, 900, session.id, account)
    }

    fun adminUsers(limit: Int): List<UserAccount> = repository.listAccounts(limit)
    fun adminUser(userId: String): UserAccount = repository.account(userId) ?: throw ApiException(ErrorCode.NOT_FOUND, "User was not found.", 404)
    fun adminSetStatus(userId: String, status: UserStatus, actorId: String, correlationId: String): UserAccount = repository.setStatus(userId, status, actorId, correlationId)
    fun grantRole(userId: String, role: String): UserAccount = repository.grantRole(userId, role, Instant.now())

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun validation(message: String): Nothing = throw ApiException(ErrorCode.VALIDATION_ERROR, message, 400)
    private fun authFailure(): ApiException = ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Invalid credentials.", 401)
}

data class RegisterCommand(val email: String?, val phone: String?, val password: String, val firstName: String, val lastName: String)
data class LoginCommand(val identifier: String, val password: String, val deviceId: String?, val platform: String?, val appVersion: String?, val ipAddress: String?)
data class SessionContext(val deviceId: String?, val platform: String?, val appVersion: String?, val ipAddress: String?)
data class OtpCommand(val userId: String?, val destination: String, val purpose: OtpPurpose)
data class ProfileCommand(val firstName: String?, val lastName: String?, val preferredLanguage: String?, val preferredCurrency: String?, val marketingEmail: Boolean?, val marketingSms: Boolean?, val marketingPush: Boolean?)
data class AddressCommand(val label: AddressLabel, val recipientName: String, val phone: String, val line1: String, val line2: String?, val city: String, val state: String, val postalCode: String, val country: String, val latitude: Double?, val longitude: Double?, val isDefault: Boolean) {
    fun toRepository() = AddressInput(label, recipientName.trim(), normalizePhone(phone), line1.trim(), line2?.trim(), city.trim(), state.trim(), postalCode.trim(), country.trim().uppercase(), latitude, longitude, isDefault)
}
data class AuthResult(val accessToken: String, val refreshToken: String, val expiresIn: Long, val sessionId: String, val account: UserAccount)
