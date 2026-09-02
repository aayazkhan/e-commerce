package com.ecommerce.seller

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SellerLifecycleTest {
    @Test
    fun sellerLifecycleAcceptsEveryImplementedForwardAndRecoveryTransition() {
        assertTrue(isValidSellerTransition(SellerStatus.PENDING, SellerStatus.REJECTED))
        assertTrue(isValidSellerTransition(SellerStatus.UNDER_REVIEW, SellerStatus.VERIFIED))
        assertTrue(isValidSellerTransition(SellerStatus.UNDER_REVIEW, SellerStatus.REJECTED))
        assertTrue(isValidSellerTransition(SellerStatus.VERIFIED, SellerStatus.ACTIVE))
        assertTrue(isValidSellerTransition(SellerStatus.ACTIVE, SellerStatus.SUSPENDED))
        assertTrue(isValidSellerTransition(SellerStatus.ACTIVE, SellerStatus.CLOSED))
        assertTrue(isValidSellerTransition(SellerStatus.SUSPENDED, SellerStatus.ACTIVE))
        assertTrue(isValidSellerTransition(SellerStatus.SUSPENDED, SellerStatus.CLOSED))
        assertTrue(isValidSellerTransition(SellerStatus.REJECTED, SellerStatus.UNDER_REVIEW))
    }

    @Test
    fun sellerLifecycleRejectsSelfTransitionsAndTransitionsOutsideEachStateRule() {
        SellerStatus.entries.forEach { status ->
            assertFalse(isValidSellerTransition(status, status))
        }
        assertFalse(isValidSellerTransition(SellerStatus.PENDING, SellerStatus.VERIFIED))
        assertFalse(isValidSellerTransition(SellerStatus.UNDER_REVIEW, SellerStatus.ACTIVE))
        assertFalse(isValidSellerTransition(SellerStatus.VERIFIED, SellerStatus.SUSPENDED))
        assertFalse(isValidSellerTransition(SellerStatus.ACTIVE, SellerStatus.REJECTED))
        assertFalse(isValidSellerTransition(SellerStatus.SUSPENDED, SellerStatus.REJECTED))
        assertFalse(isValidSellerTransition(SellerStatus.REJECTED, SellerStatus.CLOSED))
    }

    @Test
    fun lifecycleDoesNotAllowVerificationBypass() {
        assertTrue(isValidSellerTransition(SellerStatus.PENDING, SellerStatus.UNDER_REVIEW))
        assertFalse(isValidSellerTransition(SellerStatus.PENDING, SellerStatus.ACTIVE))
        assertFalse(isValidSellerTransition(SellerStatus.CLOSED, SellerStatus.ACTIVE))
    }
}
