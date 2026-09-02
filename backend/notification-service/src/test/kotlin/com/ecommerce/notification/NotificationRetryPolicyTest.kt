package com.ecommerce.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationRetryPolicyTest {
    @Test
    fun `backoff starts at five seconds and is capped`() {
        assertEquals(5, NotificationRetryPolicy.backoffSeconds(1))
        assertEquals(10, NotificationRetryPolicy.backoffSeconds(2))
        assertEquals(5, NotificationRetryPolicy.backoffSeconds(0))
        assertEquals(3600, NotificationRetryPolicy.backoffSeconds(20))
    }

    @Test
    fun `terminal status begins at maximum attempts`() {
        assertFalse(NotificationRetryPolicy.isTerminal(NotificationRetryPolicy.maxAttempts - 1))
        assertTrue(NotificationRetryPolicy.isTerminal(NotificationRetryPolicy.maxAttempts))
        assertTrue(NotificationRetryPolicy.isTerminal(NotificationRetryPolicy.maxAttempts + 1))
    }
}
