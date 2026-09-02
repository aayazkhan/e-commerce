package com.ecommerce.platform.security

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class AccessHeader(val alg: String, val typ: String, val kid: String)

@Serializable
internal data class AccessClaims(
    val subject: String,
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val tokenId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val issuer: String,
    val audience: String,
)

data class VerifiedAccessToken(
    val subject: String,
    val roles: Set<String>,
    val permissions: Set<String>,
    val tokenId: String,
)

/** Verifies the HS256 access-token contract issued by identity-service. */
class HmacJwtAccessVerifier(
    private val issuer: String,
    private val audience: String,
    private val keys: Map<String, String>,
) {
    private val json = Json { ignoreUnknownKeys = false }

    fun verify(token: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): VerifiedAccessToken {
        val parts = token.split('.')
        if (parts.size != 3) invalid()
        val header = decode(parts[0], AccessHeader.serializer())
        if (header.alg != "HS256" || header.kid !in keys) invalid()
        val expected = sign("${parts[0]}.${parts[1]}", header.kid)
        if (!MessageDigest.isEqual(expected.toByteArray(), parts[2].toByteArray())) invalid()
        val claims = decode(parts[1], AccessClaims.serializer())
        if (claims.issuer != issuer || claims.audience != audience) invalid()
        if (claims.expiresAt <= nowEpochSeconds || claims.issuedAt > nowEpochSeconds + 60) invalid()
        return VerifiedAccessToken(claims.subject, claims.roles.toSet(), claims.permissions.toSet(), claims.tokenId)
    }

    private fun sign(input: String, keyId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys.getValue(keyId).toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun <T> decode(value: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8))
    } catch (_: Exception) {
        invalid()
    }

    private fun invalid(): Nothing = throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401)
}
