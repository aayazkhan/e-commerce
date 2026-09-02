package com.ecommerce.identity.security

import com.ecommerce.identity.config.OAuthConfig
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class OAuthIdentity(
    val provider: String,
    val subject: String,
    val email: String?,
    val firstName: String,
    val lastName: String,
)

interface OAuthIdentityVerifier {
    fun verify(provider: String, credential: String): OAuthIdentity
}

class ConfiguredOAuthIdentityVerifier(
    private val config: OAuthConfig,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : OAuthIdentityVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override fun verify(provider: String, credential: String): OAuthIdentity {
        val normalizedProvider = provider.uppercase()
        val endpoint = when (normalizedProvider) {
            "GOOGLE" -> config.googleUserInfoUrl
            "APPLE" -> config.appleUserInfoUrl
            else -> null
        } ?: throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "OAuth provider is not configured.", 503, retryable = true)
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://localhost")) {
            "OAuth userinfo endpoint must use HTTPS outside localhost"
        }
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer $credential")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "OAuth provider is unavailable.", 503, retryable = true)
        }
        if (response.statusCode() !in 200..299) throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "OAuth authentication failed.", 401)
        val body = try { json.parseToJsonElement(response.body()).jsonObject } catch (_: Exception) { throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "OAuth authentication failed.", 401) }
        val subject = body["sub"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() } ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "OAuth authentication failed.", 401)
        val email = body["email"]?.toString()?.trim('"')?.lowercase()
        return OAuthIdentity(
            provider = normalizedProvider,
            subject = subject,
            email = email,
            firstName = body["given_name"]?.toString()?.trim('"') ?: "Customer",
            lastName = body["family_name"]?.toString()?.trim('"') ?: "",
        )
    }
}
