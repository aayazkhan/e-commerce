package com.ecommerce.identity.security

import com.ecommerce.identity.config.JwtConfig
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtServiceTest {
    private val service = JwtService(
        JwtConfig(
            issuer = "issuer",
            audience = "audience",
            activeKeyId = "v1",
            keys = mapOf("v1" to "test-secret-v1", "v0" to "old-secret"),
            accessTokenSeconds = 900,
            refreshTokenDays = 30,
        ),
    )

    @Test
    fun `issues and verifies only expected claims`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val token = service.issueAccessToken(
            UserAccount("usr_1", "user@example.com", null, UserStatus.ACTIVE, now, null, setOf("CUSTOMER"), emptySet()),
            now,
        )

        val claims = service.verifyAccessToken(token, now.plusSeconds(1))

        assertEquals("usr_1", claims.subject)
        assertEquals(listOf("CUSTOMER"), claims.roles)
        assertEquals("issuer", claims.issuer)
    }

    @Test
    fun `rejects tampered token`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val token = service.issueAccessToken(
            UserAccount("usr_1", null, null, UserStatus.ACTIVE, null, null, emptySet(), emptySet()),
            now,
        )

        assertFailsWith<RuntimeException> {
            service.verifyAccessToken("$token-x", now)
        }
    }

    @Test
    fun `rejects malformed expired and future-issued tokens`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val account = UserAccount("usr_1", null, null, UserStatus.ACTIVE, null, null, emptySet(), emptySet())
        assertFailsWith<RuntimeException> { service.verifyAccessToken("not.a.jwt", now) }
        assertFailsWith<RuntimeException> { service.verifyAccessToken(service.issueAccessToken(account, now), now.plusSeconds(901)) }
        assertFailsWith<RuntimeException> { service.verifyAccessToken(service.issueAccessToken(account, now.plusSeconds(120)), now) }
        assertFailsWith<RuntimeException> { service.verifyAccessToken("not-a-jwt", now) }
    }

    @Test
    fun `rejects unsupported headers and mismatched issuer or audience`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val claims = """{"sub":"usr_1","roles":[],"permissions":[],"tokenId":"token","issuedAt":${now.epochSecond},"expiresAt":${now.plusSeconds(900).epochSecond},"issuer":"issuer","audience":"audience"}"""

        assertFailsWith<RuntimeException> { service.verifyAccessToken(signedToken("RS256", "v1", claims), now) }
        assertFailsWith<RuntimeException> { service.verifyAccessToken(signedToken("HS256", "missing", claims), now) }
        assertFailsWith<RuntimeException> { service.verifyAccessToken(signedToken("HS256", "v1", claims.replace("\"issuer\":\"issuer\"", "\"issuer\":\"other\"")), now) }
        assertFailsWith<RuntimeException> { service.verifyAccessToken(signedToken("HS256", "v1", claims.replace("\"audience\":\"audience\"", "\"audience\":\"other\"")), now) }
    }

    @Test
    fun `rejects a correctly signed token with malformed claims`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")

        assertFailsWith<RuntimeException> {
            service.verifyAccessToken(signedToken("HS256", "v1", "not-json"), now)
        }
    }

    @Test
    fun `refresh tokens are random url-safe values and hashes are deterministic`() {
        val first = service.newRefreshToken()
        val second = service.newRefreshToken()
        assertEquals(64, first.length)
        assertEquals(64, second.length)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(first != second)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }

    private fun signedToken(algorithm: String, keyId: String, claims: String): String {
        fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        val input = "${encode("{\"alg\":\"$algorithm\",\"typ\":\"JWT\",\"kid\":\"$keyId\"}")}.${encode(claims)}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-secret-v1".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))
        return "$input.$signature"
    }
}
