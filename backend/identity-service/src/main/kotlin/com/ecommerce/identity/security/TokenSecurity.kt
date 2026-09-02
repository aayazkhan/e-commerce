package com.ecommerce.identity.security

import com.ecommerce.identity.config.JwtConfig
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtService(private val config: JwtConfig) {
    private val json = Json { ignoreUnknownKeys = false }
    private val random = SecureRandom()

    fun issueAccessToken(account: UserAccount, now: Instant = Instant.now()): String {
        val header = JwtHeader(alg = "HS256", typ = "JWT", kid = config.activeKeyId)
        val claims = JwtClaims(
            subject = account.id,
            roles = account.roles.toList().sorted(),
            permissions = account.permissions.toList().sorted(),
            tokenId = randomToken(18),
            issuedAt = now.epochSecond,
            expiresAt = now.plusSeconds(config.accessTokenSeconds).epochSecond,
            issuer = config.issuer,
            audience = config.audience,
        )
        val encodedHeader = encode(json.encodeToString(header))
        val encodedClaims = encode(json.encodeToString(claims))
        val input = "$encodedHeader.$encodedClaims"
        return "$input.${sign(input, config.activeKeyId)}"
    }

    fun verifyAccessToken(token: String, now: Instant = Instant.now()): JwtClaims {
        val parts = token.split('.')
        if (parts.size != 3) invalidToken()
        val header = decode<JwtHeader>(parts[0])
        if (header.alg != "HS256" || header.kid !in config.keys) invalidToken()
        val expected = sign("${parts[0]}.${parts[1]}", header.kid)
        if (!MessageDigest.isEqual(expected.toByteArray(), parts[2].toByteArray())) invalidToken()
        val claims = decode<JwtClaims>(parts[1])
        if (claims.issuer != config.issuer || claims.audience != config.audience) invalidToken()
        if (claims.expiresAt <= now.epochSecond || claims.issuedAt > now.plusSeconds(60).epochSecond) invalidToken()
        return claims
    }

    fun newRefreshToken(): String = randomToken(48)

    private fun sign(input: String, keyId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.keys.getValue(keyId).toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))
    }

    private inline fun <reified T> decode(value: String): T = try {
        json.decodeFromString(Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8))
    } catch (_: Exception) {
        invalidToken()
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun invalidToken(): Nothing = throw ApiException(
        errorCode = ErrorCode.AUTHENTICATION_REQUIRED,
        message = "Authentication is required.",
        statusCode = 401,
    )
}

@Serializable
data class JwtHeader(
    val alg: String,
    val typ: String,
    val kid: String,
)

@Serializable
data class JwtClaims(
    val subject: String,
    val roles: List<String>,
    val permissions: List<String>,
    val tokenId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val issuer: String,
    val audience: String,
)

fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
