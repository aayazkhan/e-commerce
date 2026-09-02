package com.ecommerce.platform.service

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisCacheTest {
    @Test
    fun `cache maps successful commands and only expires a newly created counter`() {
        val operations = FakeRedisOperations(value = "cached")
        val cache = RedisCache(operations)

        assertTrue(cache.ping())
        assertEquals("cached", cache.get("key"))
        cache.put("key", "value", Duration.ofSeconds(30))
        cache.delete()
        cache.delete("key", "other")

        operations.incrementResult = 1
        assertEquals(1L, cache.increment("counter", Duration.ofSeconds(45)))
        operations.incrementResult = 2
        assertEquals(2L, cache.increment("counter", Duration.ofSeconds(45)))

        operations.setResult = "OK"
        assertTrue(cache.setIfAbsent("lock", "owner", Duration.ofSeconds(10)))
        operations.setResult = "EXISTS"
        assertFalse(cache.setIfAbsent("lock", "owner", Duration.ofSeconds(10)))

        assertEquals(listOf("key", "other"), operations.deletedKeys)
        assertEquals(listOf("counter:45"), operations.expirations)
        assertEquals("key=value:30", operations.lastSetex)
    }

    @Test
    fun `cache converts redis failures into safe fallback results`() {
        val operations = FakeRedisOperations().apply {
            failPing = true
            failGet = true
            failSetex = true
            failDelete = true
            failIncrement = true
            failExpire = true
            failSetIfAbsent = true
        }
        val cache = RedisCache(operations)

        assertFalse(cache.ping())
        assertEquals(null, cache.get("key"))
        cache.put("key", "value", Duration.ofSeconds(30))
        cache.delete("key")
        assertEquals(null, cache.increment("counter", Duration.ofSeconds(45)))
        assertFalse(cache.setIfAbsent("lock", "owner", Duration.ofSeconds(10)))
        cache.close()
    }

    private class FakeRedisOperations(
        private val value: String? = null,
    ) : RedisOperations {
        var incrementResult = 0L
        var setResult: String? = null
        var failPing = false
        var failGet = false
        var failSetex = false
        var failDelete = false
        var failIncrement = false
        var failExpire = false
        var failSetIfAbsent = false
        val deletedKeys = mutableListOf<String>()
        val expirations = mutableListOf<String>()
        var lastSetex: String? = null

        override fun ping(): String {
            check(!failPing)
            return "PONG"
        }

        override fun get(key: String): String? {
            check(!failGet)
            return value
        }

        override fun setex(key: String, seconds: Long, value: String) {
            check(!failSetex)
            lastSetex = "$key=$value:$seconds"
        }

        override fun del(vararg keys: String) {
            check(!failDelete)
            deletedKeys += keys
        }

        override fun incr(key: String): Long {
            check(!failIncrement)
            return incrementResult
        }

        override fun expire(key: String, seconds: Long) {
            check(!failExpire)
            expirations += "$key:$seconds"
        }

        override fun setIfAbsent(key: String, value: String, ttlSeconds: Long): String? {
            check(!failSetIfAbsent)
            return setResult
        }
    }
}
