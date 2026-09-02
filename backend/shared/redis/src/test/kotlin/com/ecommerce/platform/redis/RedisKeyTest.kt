package com.ecommerce.platform.redis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedisKeyTest {
    @Test
    fun `scoped key is deterministic and tenant isolated`() {
        assertEquals("cart:tenant-1:user-1", RedisKey.scoped("tenant-1", "cart", "user-1").toString())
        assertEquals("cart:tenant-2:user-1", RedisKey.scoped("tenant-2", "cart", "user-1").value)
    }

    @Test
    fun `invalid key components are rejected`() {
        assertFailsWith<IllegalArgumentException> { RedisKey.scoped("", "cart", "user") }
        assertFailsWith<IllegalArgumentException> { RedisKey.scoped("tenant", "Cart", "user") }
        assertFailsWith<IllegalArgumentException> { RedisKey.scoped("tenant", "cart_1", "user") }
        assertFailsWith<IllegalArgumentException> { RedisKey.scoped("tenant", "cart", "") }
    }
}
