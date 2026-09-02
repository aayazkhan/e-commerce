package com.ecommerce.platform.service

import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import java.time.Duration

/** Best-effort cache: callers retain PostgreSQL/OpenSearch as the source of truth when Redis is unavailable. */
internal interface RedisOperations {
    fun ping(): String
    fun get(key: String): String?
    fun setex(key: String, seconds: Long, value: String)
    fun del(vararg keys: String)
    fun incr(key: String): Long
    fun expire(key: String, seconds: Long)
    fun setIfAbsent(key: String, value: String, ttlSeconds: Long): String?
}

private class LettuceRedisOperations(url: String) : RedisOperations, AutoCloseable {
    private val client = RedisClient.create(url)
    private val connection = client.connect()
    private val commands: RedisCommands<String, String> = connection.sync()

    override fun ping() = commands.ping()
    override fun get(key: String) = commands.get(key)
    override fun setex(key: String, seconds: Long, value: String) { commands.setex(key, seconds, value) }
    override fun del(vararg keys: String) { commands.del(*keys) }
    override fun incr(key: String) = commands.incr(key)
    override fun expire(key: String, seconds: Long) { commands.expire(key, seconds) }
    override fun setIfAbsent(key: String, value: String, ttlSeconds: Long) = commands.set(key, value, io.lettuce.core.SetArgs.Builder.nx().ex(ttlSeconds))

    override fun close() {
        connection.close()
        client.shutdown()
    }
}

class RedisCache private constructor(
    private val commands: RedisOperations,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    constructor(url: String) : this(LettuceRedisOperations(url))

    private constructor(delegate: LettuceRedisOperations) : this(delegate, delegate::close)

    internal constructor(commands: RedisOperations) : this(commands, {})

    fun ping(): Boolean = runCatching { commands.ping() == "PONG" }.getOrDefault(false)

    fun get(key: String): String? = runCatching { commands.get(key) }.getOrNull()

    fun put(key: String, value: String, ttl: Duration) {
        runCatching { commands.setex(key, ttl.seconds, value) }
    }

    fun delete(vararg keys: String) {
        runCatching { if (keys.isNotEmpty()) commands.del(*keys) }
    }

    fun increment(key: String, ttl: Duration): Long? = runCatching {
        val result = commands.incr(key)
        if (result == 1L) commands.expire(key, ttl.seconds)
        result
    }.getOrNull()

    fun setIfAbsent(key: String, value: String, ttl: Duration): Boolean = runCatching { commands.setIfAbsent(key, value, ttl.seconds) == "OK" }.getOrDefault(false)

    override fun close() {
        closeAction()
    }
}
