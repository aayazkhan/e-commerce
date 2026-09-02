package com.ecommerce.platform.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizationTest {
    @Test
    fun `principal maps identity and tenant to request metadata`() {
        val metadata = AuthenticatedPrincipal("user-1", setOf(Role.CUSTOMER), setOf(Permission.CART_READ), "tenant-1")
            .toRequestMetadata("req-1", "trace-1")

        assertEquals("user-1", metadata.actorId)
        assertEquals("tenant-1", metadata.tenantId)
        assertEquals("req-1", metadata.requestId)
        assertEquals("trace-1", metadata.traceId)
    }

    @Test
    fun `super admin can satisfy any permission`() {
        val decision = runCatching {
            AuthenticatedPrincipal("admin-1", setOf(Role.SUPER_ADMIN), emptySet(), null).require(Permission.REFUND_APPROVE)
        }

        assertEquals(Result.success(Unit), decision)
    }

    @Test
    fun `missing permission is denied`() {
        val principal = AuthenticatedPrincipal("user-1", setOf(Role.CUSTOMER), setOf(Permission.CART_READ), null)

        assertFailsWith<IllegalArgumentException> { principal.require(Permission.PAYMENT_CREATE) }
    }
}
