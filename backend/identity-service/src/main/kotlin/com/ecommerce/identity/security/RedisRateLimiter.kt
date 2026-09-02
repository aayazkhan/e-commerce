package com.ecommerce.identity.security

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection

interface RateLimiter {
    fun check(key: String, limit: Long, windowSeconds: Long)
}

interface RateLimitCommands {
    fun incr(key: String): Long
    fun expire(key: String, seconds: Long): Boolean
    fun ping(): String
    fun close()
}

class RedisRateLimiter private constructor(
    private val commands: RateLimitCommands,
    private val closeCommands: () -> Unit,
) : AutoCloseable, RateLimiter {
    constructor(redisUrl: String) : this(LettuceRateLimitCommands(redisUrl))

    private constructor(delegate: LettuceRateLimitCommands) : this(delegate, delegate::close)

    internal constructor(commands: RateLimitCommands) : this(commands, commands::close)

    override fun check(key: String, limit: Long, windowSeconds: Long) {
        val count = commands.incr(key)
        if (count == 1L) commands.expire(key, windowSeconds)
        if (count > limit) {
            throw ApiException(
                errorCode = ErrorCode.RATE_LIMITED,
                message = "Too many requests. Try again later.",
                statusCode = 429,
                retryable = true,
            )
        }
    }

    fun ping(): Boolean = commands.ping() == "PONG"

    override fun close() {
        closeCommands()
    }
}

private class LettuceRateLimitCommands(redisUrl: String) : RateLimitCommands {
    private val client = RedisClient.create(redisUrl)
    private val connection: StatefulRedisConnection<String, String> = client.connect()
    private val commands = connection.sync()

    override fun incr(key: String): Long = commands.incr(key)
    override fun expire(key: String, seconds: Long): Boolean = commands.expire(key, seconds)
    override fun ping(): String = commands.ping()
    override fun close() {
        connection.close()
        client.shutdown()
    }
}
