package com.ecommerce.platform.security

import com.ecommerce.platform.error.ApiException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacJwtAccessVerifierTest {
    private val issuer = "identity-service"
    private val audience = "commerce-api"
    private val keyId = "key-1"
    private val secret = "unit-test-secret"
    private val verifier = HmacJwtAccessVerifier(issuer, audience, mapOf(keyId to secret))

    @Test
    fun `valid token returns identity roles permissions and token id`() {
        val token = token()
        val verified = verifier.verify(token, nowEpochSeconds = 1_000)
        assertEquals("user-1", verified.subject)
        assertEquals(setOf("CUSTOMER", "SELLER"), verified.roles)
        assertEquals(setOf("CART_READ", "ORDER_READ"), verified.permissions)
        assertEquals("token-1", verified.tokenId)
    }

    @Test
    fun `malformed structure header key signature and claims are rejected`() {
        val valid = token()
        listOf(
            "not-a-jwt",
            token(header = "{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"$keyId\"}"),
            token(header = "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"unknown\"}"),
            valid.dropLast(1) + if (valid.last() == 'A') "B" else "A",
            token(claims = "{\"subject\":\"user-1\",\"tokenId\":\"token-1\",\"issuedAt\":1000,\"expiresAt\":2000,\"issuer\":\"wrong\",\"audience\":\"$audience\"}"),
            token(claims = "{\"subject\":\"user-1\",\"tokenId\":\"token-1\",\"issuedAt\":1000,\"expiresAt\":2000,\"issuer\":\"$issuer\",\"audience\":\"wrong\"}"),
            token(claims = "not-json"),
        ).forEach { invalid ->
            assertFailsWith<ApiException> { verifier.verify(invalid, nowEpochSeconds = 1_000) }
        }
    }

    @Test
    fun `expired and future-issued tokens are rejected`() {
        assertFailsWith<ApiException> { verifier.verify(token(expiresAt = 999), nowEpochSeconds = 1_000) }
        assertFailsWith<ApiException> { verifier.verify(token(issuedAt = 1_061), nowEpochSeconds = 1_000) }
        assertTrue(verifier.verify(token(issuedAt = 1_060, expiresAt = 2_000), nowEpochSeconds = 1_000).subject.isNotEmpty())
    }

    @Test
    fun `valid token accepts omitted optional role and permission claims`() {
        val sparse = token(
            claims = "{\"subject\":\"user-2\",\"tokenId\":\"token-2\",\"issuedAt\":900,\"expiresAt\":2000,\"issuer\":\"$issuer\",\"audience\":\"$audience\"}",
        )

        val verified = verifier.verify(sparse, nowEpochSeconds = 1_000)

        assertEquals("user-2", verified.subject)
        assertEquals(emptySet(), verified.roles)
        assertEquals(emptySet(), verified.permissions)
    }

    @Test
    fun `valid token accepts each optional claim independently`() {
        val rolesOnly = verifier.verify(
            token(claims = "{\"subject\":\"user-3\",\"roles\":[\"SELLER\"],\"tokenId\":\"token-3\",\"issuedAt\":900,\"expiresAt\":2000,\"issuer\":\"$issuer\",\"audience\":\"$audience\"}"),
            nowEpochSeconds = 1_000,
        )
        assertEquals(setOf("SELLER"), rolesOnly.roles)
        assertEquals(emptySet(), rolesOnly.permissions)

        val permissionsOnly = verifier.verify(
            token(claims = "{\"subject\":\"user-4\",\"permissions\":[\"ORDER_READ\"],\"tokenId\":\"token-4\",\"issuedAt\":900,\"expiresAt\":2000,\"issuer\":\"$issuer\",\"audience\":\"$audience\"}"),
            nowEpochSeconds = 1_000,
        )
        assertEquals(emptySet(), permissionsOnly.roles)
        assertEquals(setOf("ORDER_READ"), permissionsOnly.permissions)
    }

    @Test
    fun `access claims serializer preserves default and populated authorization claims`() {
        val sparse = AccessClaims("user-5", tokenId = "token-5", issuedAt = 900, expiresAt = 2_000, issuer = issuer, audience = audience)
        val complete = AccessClaims(
            subject = "user-6",
            roles = listOf("ADMIN", "SELLER"),
            permissions = listOf("USER_READ", "ORDER_READ"),
            tokenId = "token-6",
            issuedAt = 900,
            expiresAt = 2_000,
            issuer = issuer,
            audience = audience,
        )
        val json = Json { encodeDefaults = false; explicitNulls = false }
        assertEquals(sparse, json.decodeFromString<AccessClaims>(json.encodeToString(sparse)))
        assertEquals(complete, json.decodeFromString<AccessClaims>(json.encodeToString(complete)))
        listOf(
            sparse.copy(roles = listOf("SELLER")),
            sparse.copy(permissions = listOf("ORDER_READ")),
        ).forEach { value ->
            assertEquals(value, json.decodeFromString<AccessClaims>(json.encodeToString(value)))
        }
        val verified = verifier.verify(token(claims = json.encodeToString(complete)), nowEpochSeconds = 1_000)
        assertEquals(setOf("ADMIN", "SELLER"), verified.roles)
        assertEquals(setOf("USER_READ", "ORDER_READ"), verified.permissions)
    }

    private fun token(
        header: String = "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"$keyId\"}",
        claims: String? = null,
        issuedAt: Long = 900,
        expiresAt: Long = 2_000,
    ): String {
        val body = claims ?: "{\"subject\":\"user-1\",\"roles\":[\"CUSTOMER\",\"SELLER\"],\"permissions\":[\"CART_READ\",\"ORDER_READ\"],\"tokenId\":\"token-1\",\"issuedAt\":$issuedAt,\"expiresAt\":$expiresAt,\"issuer\":\"$issuer\",\"audience\":\"$audience\"}"
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))
        val encodedClaims = encoder.encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        val input = "$encodedHeader.$encodedClaims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))
        return "$input.$signature"
    }
}
