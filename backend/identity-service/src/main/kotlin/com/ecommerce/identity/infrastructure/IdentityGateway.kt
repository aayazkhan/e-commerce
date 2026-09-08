package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserAddress
import com.ecommerce.identity.domain.UserProfile
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.identity.security.OAuthIdentity
import java.time.Instant

interface IdentityGateway {
    fun register(email: String?, phone: String?, passwordHash: String, firstName: String, lastName: String, verificationTokenHash: String, now: Instant, eventPayload: (String) -> String): RegistrationRecord
    fun findCredentials(identifier: String): CredentialRecord?
    fun findAccountByProvider(provider: String, subject: String): UserAccount?
    fun linkProvider(userId: String, provider: String, subject: String, now: Instant): Int
    fun createOAuthAccount(identity: OAuthIdentity, passwordHash: String, now: Instant, eventPayload: (String) -> String): UserAccount
    fun recordLoginFailure(userId: String, maxAttempts: Int, lockMinutes: Long, now: Instant): Any
    fun recordLoginSuccess(userId: String, now: Instant): UserAccount
    fun createSession(userId: String, tokenFamily: String, refreshTokenHash: String, deviceId: String?, platform: String?, appVersion: String?, ipHash: String?, now: Instant, expiresAt: Instant): SessionRecord
    fun rotateRefreshToken(refreshTokenHash: String, newRefreshTokenHash: String, newSessionId: String, now: Instant, newExpiresAt: Instant): RotationRecord
    fun revokeSession(userId: String, sessionId: String, now: Instant): Int
    fun revokeAllSessions(userId: String, now: Instant): Int
    fun sessions(userId: String): List<SessionRecord>
    fun createVerificationToken(userId: String, purpose: String, tokenHash: String, now: Instant, expiresAt: Instant): Int
    fun consumeVerificationToken(tokenHash: String, purpose: String, now: Instant): UserAccount
    fun createOtp(id: String, userId: String?, purpose: String, destinationHash: String, codeHash: String, now: Instant, expiresAt: Instant): Int
    fun verifyOtp(id: String, codeHash: String, maxAttempts: Int, now: Instant, expectedUserId: String? = null, expectedPurpose: String? = null): OtpVerification
    fun resetPassword(tokenHash: String, passwordHash: String, now: Instant): Int
    fun account(userId: String): UserAccount?
    fun listAccounts(limit: Int): List<UserAccount>
    fun setStatus(userId: String, status: UserStatus, actorId: String, correlationId: String): UserAccount
    fun updateProfile(userId: String, profile: ProfileUpdate, now: Instant): UserProfile
    fun getProfile(userId: String): UserProfile
    fun listAddresses(userId: String): List<UserAddress>
    fun createAddress(userId: String, input: AddressInput, now: Instant): UserAddress
    fun updateAddress(userId: String, addressId: String, input: AddressInput, now: Instant): UserAddress
    fun deleteAddress(userId: String, addressId: String, now: Instant): Int
    fun setDefaultAddress(userId: String, addressId: String, now: Instant): UserAddress
    fun deactivate(userId: String, now: Instant): Int
    fun grantRole(userId: String, role: String, now: Instant): UserAccount
}
