package com.ecommerce.identity.domain

import com.ecommerce.identity.config.JwtConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserStatusTest {
    @Test
    fun `deleted accounts cannot transition`() {
        assertFalse(UserStatus.DELETED.canTransitionTo(UserStatus.ACTIVE))
        assertTrue(UserStatus.PENDING_VERIFICATION.canTransitionTo(UserStatus.ACTIVE))
    }

    @Test
    fun `each account lifecycle state accepts only its implemented transitions`() {
        assertTrue(UserStatus.PENDING_VERIFICATION.canTransitionTo(UserStatus.DEACTIVATED))
        assertFalse(UserStatus.PENDING_VERIFICATION.canTransitionTo(UserStatus.LOCKED))
        assertTrue(UserStatus.ACTIVE.canTransitionTo(UserStatus.SUSPENDED))
        assertFalse(UserStatus.ACTIVE.canTransitionTo(UserStatus.PENDING_VERIFICATION))
        assertTrue(UserStatus.SUSPENDED.canTransitionTo(UserStatus.DELETED))
        assertFalse(UserStatus.SUSPENDED.canTransitionTo(UserStatus.LOCKED))
        assertTrue(UserStatus.LOCKED.canTransitionTo(UserStatus.DEACTIVATED))
        assertFalse(UserStatus.LOCKED.canTransitionTo(UserStatus.SUSPENDED))
        assertTrue(UserStatus.DEACTIVATED.canTransitionTo(UserStatus.DELETED))
        assertFalse(UserStatus.DEACTIVATED.canTransitionTo(UserStatus.SUSPENDED))
    }

    @Test
    fun `normalizers and validators enforce identity boundaries`() {
        assertEquals("user@example.com", normalizeEmail(" User@Example.COM "))
        assertEquals("+919999999999", normalizePhone("+91 (999) 999-9999"))
        assertTrue(validateEmail("user@example.com"))
        assertFalse(validateEmail("not-an-email"))
        assertEquals(emptyList(), validatePassword("Strong-password-123!"))
        assertTrue(validatePassword("weak").size >= 4)
        assertTrue(validatePassword("UPPERCASE-123!").contains("Password must contain a lowercase letter"))
        assertFailsWith<IllegalArgumentException> { JwtConfig("issuer", "audience", "missing", emptyMap(), 900, 30) }
    }
}
