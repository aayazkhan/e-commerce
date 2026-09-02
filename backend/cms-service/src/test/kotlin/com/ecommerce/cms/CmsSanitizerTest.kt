package com.ecommerce.cms

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CmsSanitizerTest {
    @Test
    fun removesExecutableMarkupAndEventHandlers() {
        val clean = sanitizeHtml("<script>alert(1)</script><p onclick=alert(2)>safe</p><a href='javascript:alert(3)'>link</a>")
        assertFalse(clean.contains("script", ignoreCase = true))
        assertFalse(clean.contains("onclick", ignoreCase = true))
        assertFalse(clean.contains("javascript:", ignoreCase = true))
        assertTrue(clean.contains("safe"))
    }
}
