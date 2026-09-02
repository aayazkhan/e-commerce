package com.ecommerce.identity.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IdentityConfigTest {
    @Test
    fun `configuration parses required values and applies safe defaults`() {
        val config = IdentityConfig.from(config())
        assertEquals(15, config.database.maximumPoolSize)
        assertEquals(2_000, config.database.connectionTimeoutMillis)
        assertEquals(900, config.jwt.accessTokenSeconds)
        assertEquals(30, config.jwt.refreshTokenDays)
        assertEquals(8, config.security.maxLoginAttempts)
        assertEquals(15, config.security.lockMinutes)
        assertEquals(300, config.security.otpExpirySeconds)
        assertEquals(5, config.security.otpMaxAttempts)
        assertEquals(60, config.security.otpResendSeconds)
        assertNull(config.challengeDelivery.url)
        assertNull(config.oauth.googleUserInfoUrl)
        assertEquals("identity.events.v1", config.kafka.topic)
    }

    @Test
    fun `configuration parses optional overrides and multiple signing keys`() {
        val config = IdentityConfig.from(config(
            "identity.database.maximumPoolSize" to "25",
            "identity.database.connectionTimeoutMillis" to "5000",
            "identity.jwt.accessTokenSeconds" to "1200",
            "identity.jwt.refreshTokenDays" to "60",
            "identity.security.maxLoginAttempts" to "4",
            "identity.security.lockMinutes" to "20",
            "identity.security.otpExpirySeconds" to "180",
            "identity.security.otpMaxAttempts" to "3",
            "identity.security.otpResendSeconds" to "30",
            "identity.challengeDelivery.url" to "http://delivery",
            "identity.challengeDelivery.bearerToken" to "secret",
            "identity.oauth.googleUserInfoUrl" to "http://google",
            "identity.oauth.appleUserInfoUrl" to "http://apple",
            "identity.jwt.topic" to "unused",
            "identity.kafka.topic" to "identity.custom.v1",
        ))
        assertEquals(25, config.database.maximumPoolSize)
        assertEquals(5000, config.database.connectionTimeoutMillis)
        assertEquals(1200, config.jwt.accessTokenSeconds)
        assertEquals(60, config.jwt.refreshTokenDays)
        assertEquals(4, config.security.maxLoginAttempts)
        assertEquals("http://delivery", config.challengeDelivery.url)
        assertEquals("secret", config.challengeDelivery.bearerToken)
        assertEquals("http://google", config.oauth.googleUserInfoUrl)
        assertEquals("http://apple", config.oauth.appleUserInfoUrl)
        assertEquals("identity.custom.v1", config.kafka.topic)
    }

    @Test
    fun `configuration rejects missing required values malformed keys and invalid token lifetimes`() {
        assertFailsWith<IllegalStateException> { IdentityConfig.from(config("identity.jwt.keys" to "v1=secret", remove = "identity.redis.url")) }
        assertFailsWith<IllegalArgumentException> { IdentityConfig.from(config("identity.jwt.keys" to "malformed")) }
        assertFailsWith<IllegalArgumentException> { IdentityConfig.from(config("identity.jwt.keys" to "v1=")) }
        assertFailsWith<IllegalArgumentException> { IdentityConfig.from(config("identity.jwt.keys" to "=secret")) }
        assertFailsWith<IllegalArgumentException> { IdentityConfig.from(config("identity.jwt.keys" to "v1=secret,v2=")) }
        assertFailsWith<IllegalArgumentException> { JwtConfig("issuer", "audience", "v1", mapOf("v1" to "secret"), 59, 30) }
        assertFailsWith<IllegalArgumentException> { JwtConfig("issuer", "audience", "v1", mapOf("v1" to "secret"), 900, 366) }
    }

    @Test
    fun `configuration preserves trimmed multiple JWT signing keys`() {
        val config = IdentityConfig.from(config("identity.jwt.keys" to " v1 = secret , v0 = old-secret "))

        assertEquals(mapOf("v1" to "secret", "v0" to "old-secret"), config.jwt.keys)
    }

    private fun config(vararg overrides: Pair<String, String>, remove: String? = null): MapApplicationConfig {
        val values = mutableMapOf(
            "identity.database.url" to "jdbc:postgresql://localhost/identity",
            "identity.database.username" to "identity",
            "identity.database.password" to "secret",
            "identity.redis.url" to "redis://localhost",
            "identity.jwt.issuer" to "issuer",
            "identity.jwt.audience" to "audience",
            "identity.jwt.activeKeyId" to "v1",
            "identity.jwt.keys" to "v1=secret",
            "identity.kafka.bootstrapServers" to "localhost:9092",
            "identity.kafka.tenantId" to "tenant-1",
        )
        values.putAll(overrides)
        remove?.let(values::remove)
        return MapApplicationConfig().also { values.forEach { (key, value) -> it.put(key, value) } }
    }
}
