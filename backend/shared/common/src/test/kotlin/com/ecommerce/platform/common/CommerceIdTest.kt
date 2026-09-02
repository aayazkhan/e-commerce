package com.ecommerce.platform.common

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommerceIdTest {
    @Test
    fun `generated IDs carry the requested prefix`() {
        val id = CommerceId.new("usr")
        assertTrue(id.value.startsWith("usr_"))
        assertTrue(id.toString() == id.value)
    }

    @Test
    fun `blank IDs are rejected`() {
        assertFailsWith<IllegalArgumentException> { CommerceId(" ") }
    }
}
