package com.ecommerce.identity.http

import com.ecommerce.identity.application.AuthResult
import com.ecommerce.identity.domain.AddressLabel
import com.ecommerce.identity.domain.OtpPurpose
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserAddress
import com.ecommerce.identity.domain.UserProfile
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.identity.infrastructure.SessionRecord
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityDtosTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `request DTOs preserve optional fields and map normalized commands`() {
        val register = RegisterRequest("User@Example.com", "+91 9999999999", "Strong-password-123!", "Ada", "Lovelace")
        assertEquals(register.email, register.toCommand().email)
        assertEquals("ANDROID", LoginRequest("user@example.com", "password", "device", "ANDROID", "1.0").toCommand("127.0.0.1").platform)
        assertEquals("en", ProfileRequest(preferredLanguage = "en").toCommand().preferredLanguage)
        assertEquals(AddressLabel.WORK, AddressRequest("work", "Ada", "+91 9999999999", "Street", city = "Pune", state = "MH", postalCode = "411001", country = "in").toCommand().label)
        assertEquals(OtpPurpose.LOGIN, OtpRequest(destination = "user@example.com", purpose = "login").toCommand().purpose)
        assertEquals("device", OAuthRequest("credential", "device", "IOS", "2.0").deviceId)
        assertEquals(true, LogoutRequest(allSessions = true).allSessions)
        assertEquals("token", RefreshRequest("token").refreshToken)
        assertEquals("identifier", PasswordForgotRequest("identifier").identifier)
        assertEquals("password", PasswordResetRequest("reset", "password").password)
        assertEquals("challenge", OtpVerifyRequest("challenge", "123456").challengeId)
    }

    @Test
    fun `request serialization handles defaults nulls and complete address values`() {
        val minimal = json.decodeFromString<RegisterRequest>("{\"password\":\"Strong-password-123!\",\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}")
        assertNull(minimal.email)
        assertNull(minimal.phone)
        val address = AddressRequest("HOME", "Ada", "+91 9999999999", "Line 1", "Line 2", "Pune", "MH", "411001", "IN", 18.52, 73.85, true)
        val decoded = json.decodeFromString<AddressRequest>(json.encodeToString(address))
        assertEquals(address, decoded)
        assertEquals("{}", json.encodeToString(ProfileRequest()))
        assertEquals("{\"status\":\"ACTIVE\"}", json.encodeToString(AdminUserStatusRequest("ACTIVE")))
    }

    @Test
    fun `response mappings expose verification sorting optional values and timestamps`() {
        val account = UserAccount("user-1", "user@example.com", null, UserStatus.ACTIVE, now, null, setOf("Z", "A"), setOf("P2", "P1"))
        assertEquals(listOf("A", "Z"), account.toResponse().roles)
        assertEquals(listOf("P1", "P2"), account.toResponse().permissions)
        assertEquals(true, account.toResponse().emailVerified)
        assertEquals(false, account.toResponse().phoneVerified)

        val profile = UserProfile("user-1", "Ada", "Lovelace", null, "F", null, "en", "INR", true, false, true)
        assertEquals(profile.firstName, profile.toResponse().firstName)
        assertNull(profile.toResponse().dateOfBirth)
        val address = UserAddress("address-1", "user-1", AddressLabel.HOME, "Ada", "+919999999999", "Line 1", null, "Pune", "MH", "411001", "IN", null, null, true)
        assertEquals("HOME", address.toResponse().label)
        assertNull(address.toResponse().line2)

        val session = SessionRecord("session-1", "user-1", null, "IOS", null, now, now.plusSeconds(1), now.plusSeconds(3600))
        assertNull(session.toResponse().deviceId)
        assertEquals(now.toString(), session.toResponse().createdAt)

        val auth = AuthResult("access", "refresh", 900, "session-1", account).toResponse()
        assertEquals("Bearer", auth.tokenType)
        assertEquals("user-1", auth.user.id)
        assertEquals("challenge", ChallengeResponse("challenge").challengeId)
        assertEquals("message", MessageResponse("message").message)
    }
}
