package com.ecommerce.platform.redis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

class CachePolicyTest {
    @Test
    fun `standard policies have bounded positive lifetimes`() {
        assertEquals(5.minutes, CachePolicies.productDetail.ttl)
        assertEquals(1.minutes, CachePolicies.listing.ttl)
        assertEquals(10, CachePolicies.productDetail.jitterPercent)
    }

    @Test
    fun `cache policy rejects unsafe values`() {
        assertFailsWith<IllegalArgumentException> { CachePolicy(kotlin.time.Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { CachePolicy(1.minutes, staleWhileRevalidate = (-1).minutes) }
        assertFailsWith<IllegalArgumentException> { CachePolicy(1.minutes, jitterPercent = -1) }
        assertFailsWith<IllegalArgumentException> { CachePolicy(1.minutes, jitterPercent = 51) }
    }
}
