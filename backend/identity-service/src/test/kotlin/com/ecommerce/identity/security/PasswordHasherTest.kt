package com.ecommerce.identity.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {
    @Test
    fun `argon2 hash verifies only the original password`() {
        val hasher = PasswordHasher()
        val hash = hasher.hash("Strong-password-123!")

        assertTrue(hasher.verify(hash, "Strong-password-123!"))
        assertFalse(hasher.verify(hash, "Wrong-password-123!"))
    }
}
