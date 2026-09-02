package com.ecommerce.identity.http

import com.ecommerce.identity.domain.AddressLabel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityDtoSerializationCompletenessTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val explicitJson = Json { encodeDefaults = true; explicitNulls = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `every identity request DTO round trips complete and default forms`() {
        roundTrip(RegisterRequest("user@example.com", "+919999999999", "Strong-password-123!", "Ada", "Lovelace"), RegisterRequest.serializer())
        roundTrip(LoginRequest("user@example.com", "secret", "device-1", "ANDROID", "1.2.3"), LoginRequest.serializer())
        roundTrip(RefreshRequest("refresh"), RefreshRequest.serializer())
        roundTrip(OAuthRequest("credential", "device-1", "IOS", "2.0"), OAuthRequest.serializer())
        roundTrip(LogoutRequest("session-1", true), LogoutRequest.serializer())
        roundTrip(PasswordForgotRequest("user@example.com"), PasswordForgotRequest.serializer())
        roundTrip(PasswordResetRequest("reset", "Strong-password-123!"), PasswordResetRequest.serializer())
        roundTrip(VerificationRequest("verification"), VerificationRequest.serializer())
        roundTrip(OtpRequest("user-1", "user@example.com", "EMAIL_VERIFICATION"), OtpRequest.serializer())
        roundTrip(OtpVerifyRequest("challenge-1", "123456"), OtpVerifyRequest.serializer())
        roundTrip(ProfileRequest("Ada", "Lovelace", "en", "INR", true, false, true), ProfileRequest.serializer())
        roundTrip(AddressRequest("HOME", "Ada", "+919999999999", "Line 1", "Line 2", "Pune", "MH", "411001", "IN", 18.52, 73.85, true), AddressRequest.serializer())
        roundTrip(MessageResponse("done"), MessageResponse.serializer())
        roundTrip(AdminUserStatusRequest("SUSPENDED"), AdminUserStatusRequest.serializer())
        roundTrip(ChallengeResponse("challenge-1"), ChallengeResponse.serializer())
        assertEquals(LogoutRequest(), json.decodeFromString(LogoutRequest.serializer(), "{}"))
        assertEquals(ProfileRequest(), json.decodeFromString(ProfileRequest.serializer(), "{}"))
        assertEquals(AddressRequest("OTHER", "Ada", "phone", "line", city = "Pune", state = "MH", postalCode = "411001", country = "IN"), json.decodeFromString(AddressRequest.serializer(), "{\"label\":\"OTHER\",\"recipientName\":\"Ada\",\"phone\":\"phone\",\"line1\":\"line\",\"city\":\"Pune\",\"state\":\"MH\",\"postalCode\":\"411001\",\"country\":\"IN\"}"))
    }

    @Test
    fun `every identity response DTO round trips nullable and nested forms`() {
        val user = UserResponse("user-1", "user@example.com", null, "ACTIVE", listOf("CUSTOMER"), listOf("ORDER_READ"), true, false)
        val auth = AuthResponse("access", "refresh", 900, sessionId = "session-1", user = user)
        roundTrip(user, UserResponse.serializer())
        roundTrip(auth, AuthResponse.serializer())
        roundTrip(OtpVerificationResponse(null), OtpVerificationResponse.serializer())
        roundTrip(OtpVerificationResponse("user-1", auth), OtpVerificationResponse.serializer())
        roundTrip(SessionResponse("session-1", null, "IOS", null, "2026-08-20T00:00:00Z", "2026-08-20T00:01:00Z", "2026-08-21T00:00:00Z"), SessionResponse.serializer())
        roundTrip(AddressResponse("address-1", AddressLabel.HOME.name, "Ada", "phone", "line", null, "Pune", "MH", "411001", "IN", null, null, false), AddressResponse.serializer())
        roundTrip(ProfileResponse("user-1", "Ada", "Lovelace", null, null, null, "en", "INR", false, false, false), ProfileResponse.serializer())
        assertEquals("Bearer", json.decodeFromString(AuthResponse.serializer(), json.encodeToString(auth)).tokenType)
    }

    @Test
    fun `identity DTOs decode sparse payloads through every optional and default field`() {
        assertEquals(LoginRequest("user@example.com", "secret"), json.decodeFromString(LoginRequest.serializer(), """{"identifier":"user@example.com","password":"secret"}"""))
        assertEquals(OAuthRequest("credential"), json.decodeFromString(OAuthRequest.serializer(), """{"credential":"credential"}"""))
        assertEquals(LogoutRequest(), json.decodeFromString(LogoutRequest.serializer(), "{}"))
        assertEquals(OtpRequest(destination = "user@example.com", purpose = "LOGIN"), json.decodeFromString(OtpRequest.serializer(), """{"destination":"user@example.com","purpose":"LOGIN"}"""))
        assertEquals(ProfileRequest(), json.decodeFromString(ProfileRequest.serializer(), "{}"))
        assertEquals(
            AddressRequest("HOME", "Ada", "phone", "line", city = "Pune", state = "MH", postalCode = "411001", country = "IN"),
            json.decodeFromString(AddressRequest.serializer(), """{"label":"HOME","recipientName":"Ada","phone":"phone","line1":"line","city":"Pune","state":"MH","postalCode":"411001","country":"IN"}"""),
        )

        val sparseUser = json.decodeFromString(UserResponse.serializer(), """{"id":"user-1","email":null,"phone":null,"status":"ACTIVE","roles":[],"permissions":[],"emailVerified":false,"phoneVerified":false}""")
        assertNull(sparseUser.email)
        assertNull(sparseUser.phone)
        assertEquals(AuthResponse("access", "refresh", 900, sessionId = "session-1", user = sparseUser), json.decodeFromString(AuthResponse.serializer(), """{"accessToken":"access","refreshToken":"refresh","expiresIn":900,"sessionId":"session-1","user":{"id":"user-1","email":null,"phone":null,"status":"ACTIVE","roles":[],"permissions":[],"emailVerified":false,"phoneVerified":false}}"""))
        assertEquals(OtpVerificationResponse(null), json.decodeFromString(OtpVerificationResponse.serializer(), """{"userId":null}"""))
        assertEquals(SessionResponse("session-1", null, null, null, "2026-08-20T00:00:00Z", "2026-08-20T00:01:00Z", "2026-08-21T00:00:00Z"), json.decodeFromString(SessionResponse.serializer(), """{"id":"session-1","deviceId":null,"platform":null,"appVersion":null,"createdAt":"2026-08-20T00:00:00Z","lastActiveAt":"2026-08-20T00:01:00Z","expiresAt":"2026-08-21T00:00:00Z"}"""))
        assertEquals(AddressResponse("address-1", "HOME", "Ada", "phone", "line", null, "Pune", "MH", "411001", "IN", null, null, false), json.decodeFromString(AddressResponse.serializer(), """{"id":"address-1","label":"HOME","recipientName":"Ada","phone":"phone","line1":"line","line2":null,"city":"Pune","state":"MH","postalCode":"411001","country":"IN","latitude":null,"longitude":null,"isDefault":false}"""))
        assertEquals(ProfileResponse("user-1", "Ada", "Lovelace", null, null, null, "en", "INR", false, false, false), json.decodeFromString(ProfileResponse.serializer(), """{"userId":"user-1","firstName":"Ada","lastName":"Lovelace","dateOfBirth":null,"gender":null,"profileImageUrl":null,"preferredLanguage":"en","preferredCurrency":"INR","marketingEmail":false,"marketingSms":false,"marketingPush":false}"""))
    }

    @Test
    fun `profile request serialization covers each optional preference representation`() {
        val variants = listOf(
            ProfileRequest(firstName = "Ada"),
            ProfileRequest(lastName = "Lovelace"),
            ProfileRequest(preferredLanguage = "en"),
            ProfileRequest(preferredCurrency = "INR"),
            ProfileRequest(marketingEmail = true),
            ProfileRequest(marketingSms = false),
            ProfileRequest(marketingPush = true),
        )

        variants.forEach { value ->
            assertEquals(value, compactJson.decodeFromString(ProfileRequest.serializer(), compactJson.encodeToString(ProfileRequest.serializer(), value)))
        }
        assertEquals(ProfileRequest(), explicitJson.decodeFromString(ProfileRequest.serializer(), explicitJson.encodeToString(ProfileRequest.serializer(), ProfileRequest())))
    }

    @Test
    fun `compact identity serialization exercises omitted optional request and response fields`() {
        val register = RegisterRequest(password = "Strong-password-123!", firstName = "Ada", lastName = "Lovelace")
        val login = LoginRequest("user@example.com", "secret")
        val oauth = OAuthRequest("credential")
        val logout = LogoutRequest()
        val otp = OtpRequest(destination = "user@example.com", purpose = "LOGIN")
        val address = AddressRequest("HOME", "Ada", "phone", "line", city = "Pune", state = "MH", postalCode = "411001", country = "IN")
        val user = UserResponse("user-1", null, null, "ACTIVE", emptyList(), emptyList(), false, false)
        val auth = AuthResponse("access", "refresh", 900, sessionId = "session-1", user = user)
        val verification = OtpVerificationResponse("user-1")

        assertEquals(register, compactJson.decodeFromString(RegisterRequest.serializer(), compactJson.encodeToString(RegisterRequest.serializer(), register)))
        assertEquals(login, compactJson.decodeFromString(LoginRequest.serializer(), compactJson.encodeToString(LoginRequest.serializer(), login)))
        assertEquals(oauth, compactJson.decodeFromString(OAuthRequest.serializer(), compactJson.encodeToString(OAuthRequest.serializer(), oauth)))
        assertEquals(logout, compactJson.decodeFromString(LogoutRequest.serializer(), compactJson.encodeToString(LogoutRequest.serializer(), logout)))
        assertEquals(otp, compactJson.decodeFromString(OtpRequest.serializer(), compactJson.encodeToString(OtpRequest.serializer(), otp)))
        assertEquals(address, compactJson.decodeFromString(AddressRequest.serializer(), compactJson.encodeToString(AddressRequest.serializer(), address)))
        assertEquals(auth, compactJson.decodeFromString(AuthResponse.serializer(), compactJson.encodeToString(AuthResponse.serializer(), auth)))
        assertEquals(verification, compactJson.decodeFromString(OtpVerificationResponse.serializer(), compactJson.encodeToString(OtpVerificationResponse.serializer(), verification)))
        listOf(
            login.copy(deviceId = "device-1"),
            login.copy(platform = "ANDROID"),
            login.copy(appVersion = "1.2.3"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(LoginRequest.serializer(), compactJson.encodeToString(LoginRequest.serializer(), value)))
        }
        listOf(
            oauth.copy(deviceId = "device-1"),
            oauth.copy(platform = "IOS"),
            oauth.copy(appVersion = "2.0"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(OAuthRequest.serializer(), compactJson.encodeToString(OAuthRequest.serializer(), value)))
        }
        listOf(
            address.copy(line2 = "Floor 2"),
            address.copy(latitude = 18.52),
            address.copy(longitude = 73.85),
            address.copy(isDefault = true),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(AddressRequest.serializer(), compactJson.encodeToString(AddressRequest.serializer(), value)))
        }
    }

    @Test
    fun `identity response DTOs preserve every nullable and default value`() {
        val completeUser = UserResponse("user-1", "user@example.com", "+919999999999", "ACTIVE", listOf("CUSTOMER"), listOf("PROFILE_READ"), true, true)
        val completeAuth = AuthResponse("access", "refresh", 900, "Token", "session-1", completeUser)
        val completeSession = SessionResponse("session-1", "device-1", "ANDROID", "1.2.3", "created", "active", "expires")
        val completeAddress = AddressResponse("address-1", "HOME", "Ada", "phone", "line", "Floor 2", "Pune", "MH", "411001", "IN", 18.52, 73.85, true)
        val completeProfile = ProfileResponse("user-1", "Ada", "Lovelace", "2000-01-01", "F", "https://example.test/avatar", "en", "INR", true, true, true)

        assertEquals(completeUser, compactJson.decodeFromString(UserResponse.serializer(), compactJson.encodeToString(UserResponse.serializer(), completeUser)))
        assertEquals(completeAuth, compactJson.decodeFromString(AuthResponse.serializer(), compactJson.encodeToString(AuthResponse.serializer(), completeAuth)))
        assertEquals(completeSession, compactJson.decodeFromString(SessionResponse.serializer(), compactJson.encodeToString(SessionResponse.serializer(), completeSession)))
        assertEquals(completeAddress, compactJson.decodeFromString(AddressResponse.serializer(), compactJson.encodeToString(AddressResponse.serializer(), completeAddress)))
        assertEquals(completeProfile, compactJson.decodeFromString(ProfileResponse.serializer(), compactJson.encodeToString(ProfileResponse.serializer(), completeProfile)))
        assertEquals("Token", completeAuth.tokenType)
        assertEquals(18.52, completeAddress.latitude)
        assertEquals("https://example.test/avatar", completeProfile.profileImageUrl)
    }

    private fun <T> roundTrip(value: T, serializer: KSerializer<T>) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }
}
