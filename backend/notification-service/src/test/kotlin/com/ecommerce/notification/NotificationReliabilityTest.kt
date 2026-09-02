package com.ecommerce.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationReliabilityTest {
    @Test fun `retry policy is bounded and exponential`() {
        assertEquals(listOf(5,10,20,40), (1..4).map(NotificationRetryPolicy::backoffSeconds))
        assertFalse(NotificationRetryPolicy.isTerminal(4))
        assertTrue(NotificationRetryPolicy.isTerminal(5))
    }
}
