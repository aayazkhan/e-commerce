package com.ecommerce.flags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureFlagDeterminismTest {
    @Test
    fun rolloutBucketIsStableAndBounded() {
        val first = deterministicBucket("user-42", "checkout.v2")
        assertEquals(first, deterministicBucket("user-42", "checkout.v2"))
        assertTrue(first in 0..9999)
    }
}
