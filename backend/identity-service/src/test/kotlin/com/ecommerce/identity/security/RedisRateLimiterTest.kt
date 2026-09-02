package com.ecommerce.identity.security

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisRateLimiterTest {
    @Test
    fun `first request sets expiry and requests within limit pass`() {
        val commands = FakeCommands(count = 1, response = "PONG")
        val limiter = RedisRateLimiter(commands)

        limiter.check("login:user", 3, 60)

        assertEquals(listOf("login:user"), commands.incremented)
        assertEquals(listOf("login:user:60"), commands.expirations)
        assertTrue(limiter.ping())
        limiter.close()
        assertEquals(1, commands.closed)
    }

    @Test
    fun `subsequent request does not reset expiry and limit violation is retryable`() {
        val commands = FakeCommands(count = 4, response = "PONG")
        val limiter = RedisRateLimiter(commands)

        val error = assertFailsWith<ApiException> { limiter.check("otp:user", 3, 120) }

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        assertEquals(429, error.statusCode)
        assertTrue(error.retryable)
        assertTrue(commands.expirations.isEmpty())
    }

    @Test
    fun `non-pong redis response reports unavailable health`() {
        val commands = FakeCommands(count = 1, response = "NOPE")
        val limiter = RedisRateLimiter(commands)

        assertFalse(limiter.ping())
    }

    private class FakeCommands(private val count: Long, private val response: String) : RateLimitCommands {
        val incremented = mutableListOf<String>()
        val expirations = mutableListOf<String>()
        var closed = 0

        override fun incr(key: String): Long {
            incremented += key
            return count
        }

        override fun expire(key: String, seconds: Long): Boolean {
            expirations += "$key:$seconds"
            return true
        }

        override fun ping(): String = response
        override fun close() { closed++ }
    }
}
