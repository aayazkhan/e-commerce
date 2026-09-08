package com.ecommerce.identity.http

import com.ecommerce.identity.application.AddressCommand
import com.ecommerce.identity.application.AuthResult
import com.ecommerce.identity.application.LoginCommand
import com.ecommerce.identity.application.OtpCommand
import com.ecommerce.identity.application.ProfileCommand
import com.ecommerce.identity.application.RegisterCommand
import com.ecommerce.identity.domain.AddressLabel
import com.ecommerce.identity.domain.OtpPurpose
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserAddress
import com.ecommerce.identity.domain.UserProfile
import com.ecommerce.identity.infrastructure.SessionRecord
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(val email: String? = null, val phone: String? = null, val password: String, val firstName: String, val lastName: String)

@Serializable
data class LoginRequest(val identifier: String, val password: String, val deviceId: String? = null, val platform: String? = null, val appVersion: String? = null)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class OAuthRequest(val credential: String, val deviceId: String? = null, val platform: String? = null, val appVersion: String? = null)

@Serializable
data class LogoutRequest(val sessionId: String? = null, val allSessions: Boolean = false)

@Serializable
data class PasswordForgotRequest(val identifier: String)

@Serializable
data class PasswordResetRequest(val token: String, val password: String)

@Serializable
data class VerificationRequest(val token: String)

@Serializable
data class OtpRequest(val userId: String? = null, val destination: String, val purpose: String)

@Serializable
data class OtpVerifyRequest(val challengeId: String, val code: String)

@Serializable
data class ProfileRequest(val firstName: String? = null, val lastName: String? = null, val preferredLanguage: String? = null, val preferredCurrency: String? = null, val marketingEmail: Boolean? = null, val marketingSms: Boolean? = null, val marketingPush: Boolean? = null)

@Serializable
data class AddressRequest(val label: String, val recipientName: String, val phone: String, val line1: String, val line2: String? = null, val city: String, val state: String, val postalCode: String, val country: String, val latitude: Double? = null, val longitude: Double? = null, val isDefault: Boolean = false)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class AdminUserStatusRequest(val status: String)

@Serializable
data class GrantRoleRequest(val role: String)

@Serializable
data class ChallengeResponse(val challengeId: String)

@Serializable
data class OtpVerificationResponse(val userId: String?, val auth: AuthResponse? = null)

@Serializable
data class AuthResponse(val accessToken: String, val refreshToken: String, val expiresIn: Long, val tokenType: String = "Bearer", val sessionId: String, val user: UserResponse)

@Serializable
data class UserResponse(val id: String, val email: String?, val phone: String?, val status: String, val roles: List<String>, val permissions: List<String>, val emailVerified: Boolean, val phoneVerified: Boolean)

@Serializable
data class SessionResponse(val id: String, val deviceId: String?, val platform: String?, val appVersion: String?, val createdAt: String, val lastActiveAt: String, val expiresAt: String)

@Serializable
data class AddressResponse(val id: String, val label: String, val recipientName: String, val phone: String, val line1: String, val line2: String?, val city: String, val state: String, val postalCode: String, val country: String, val latitude: Double?, val longitude: Double?, val isDefault: Boolean)

fun RegisterRequest.toCommand() = RegisterCommand(email, phone, password, firstName, lastName)
fun LoginRequest.toCommand(ip: String?) = LoginCommand(identifier, password, deviceId, platform, appVersion, ip)
fun ProfileRequest.toCommand() = ProfileCommand(firstName, lastName, preferredLanguage, preferredCurrency, marketingEmail, marketingSms, marketingPush)
fun AddressRequest.toCommand(): AddressCommand = AddressCommand(AddressLabel.valueOf(label.uppercase()), recipientName, phone, line1, line2, city, state, postalCode, country, latitude, longitude, isDefault)
fun OtpRequest.toCommand(): OtpCommand = OtpCommand(userId, destination, OtpPurpose.valueOf(purpose.uppercase()))
fun AuthResult.toResponse() = AuthResponse(accessToken, refreshToken, expiresIn, "Bearer", sessionId, account.toResponse())
fun UserAccount.toResponse() = UserResponse(id, email, phone, status.name, roles.sorted(), permissions.sorted(), emailVerifiedAt != null, phoneVerifiedAt != null)
fun UserProfile.toResponse() = ProfileResponse(userId, firstName, lastName, dateOfBirth, gender, profileImageUrl, preferredLanguage, preferredCurrency, marketingEmail, marketingSms, marketingPush)
fun UserAddress.toResponse() = AddressResponse(id, label.name, recipientName, phone, line1, line2, city, state, postalCode, country, latitude, longitude, isDefault)
fun SessionRecord.toResponse() = SessionResponse(id, deviceId, platform, appVersion, createdAt.toString(), lastActiveAt.toString(), expiresAt.toString())

@Serializable
data class ProfileResponse(val userId: String, val firstName: String, val lastName: String, val dateOfBirth: String?, val gender: String?, val profileImageUrl: String?, val preferredLanguage: String, val preferredCurrency: String, val marketingEmail: Boolean, val marketingSms: Boolean, val marketingPush: Boolean)
