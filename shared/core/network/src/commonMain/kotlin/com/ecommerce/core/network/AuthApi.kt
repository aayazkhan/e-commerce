package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

/** Mirrors backend/identity-service/.../IdentityDtos.kt exactly (LoginRequest, AuthResponse, UserResponse). */
@Serializable
data class LoginRequest(val identifier: String, val password: String)

@Serializable
data class RegisterRequest(val email: String? = null, val phone: String? = null, val password: String, val firstName: String, val lastName: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val tokenType: String = "Bearer",
    val sessionId: String,
    val user: UserResponse,
)

@Serializable
data class UserResponse(
    val id: String,
    val email: String? = null,
    val phone: String? = null,
    val status: String,
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
)

class AuthApi(private val client: ApiClient) {
    suspend fun login(identifier: String, password: String): ApiResult<AuthResponse> =
        client.post("/api/v1/auth/login", LoginRequest(identifier, password))

    suspend fun register(email: String, password: String, firstName: String, lastName: String): ApiResult<UserResponse> =
        client.post("/api/v1/auth/register", RegisterRequest(email = email, password = password, firstName = firstName, lastName = lastName))
}
