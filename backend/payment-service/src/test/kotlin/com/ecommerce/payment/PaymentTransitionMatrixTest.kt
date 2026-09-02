package com.ecommerce.payment

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaymentTransitionMatrixTest {
    @Test
    fun `every payment status is idempotent`() {
        PaymentStatus.entries.forEach { status ->
            assertTrue(runCatching { assertPaymentTransition(status, status) }.isSuccess)
        }
    }

    @Test
    fun `every documented payment transition is accepted`() {
        val validTransitions = listOf(
            PaymentStatus.CREATED to PaymentStatus.PROCESSING,
            PaymentStatus.CREATED to PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.CREATED to PaymentStatus.AUTHORIZED,
            PaymentStatus.CREATED to PaymentStatus.FAILED,
            PaymentStatus.CREATED to PaymentStatus.CANCELLED,
            PaymentStatus.REQUIRES_ACTION to PaymentStatus.PROCESSING,
            PaymentStatus.REQUIRES_ACTION to PaymentStatus.AUTHORIZED,
            PaymentStatus.REQUIRES_ACTION to PaymentStatus.CAPTURED,
            PaymentStatus.REQUIRES_ACTION to PaymentStatus.FAILED,
            PaymentStatus.REQUIRES_ACTION to PaymentStatus.EXPIRED,
            PaymentStatus.REQUIRES_ACTION to PaymentStatus.CANCELLED,
            PaymentStatus.PROCESSING to PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING to PaymentStatus.AUTHORIZED,
            PaymentStatus.PROCESSING to PaymentStatus.CAPTURED,
            PaymentStatus.PROCESSING to PaymentStatus.FAILED,
            PaymentStatus.PROCESSING to PaymentStatus.CANCELLED,
            PaymentStatus.AUTHORIZED to PaymentStatus.CAPTURED,
            PaymentStatus.AUTHORIZED to PaymentStatus.CANCELLED,
            PaymentStatus.AUTHORIZED to PaymentStatus.REFUND_PENDING,
            PaymentStatus.CAPTURED to PaymentStatus.REFUND_PENDING,
            PaymentStatus.CAPTURED to PaymentStatus.PARTIALLY_REFUNDED,
            PaymentStatus.CAPTURED to PaymentStatus.REFUNDED,
            PaymentStatus.REFUND_PENDING to PaymentStatus.PARTIALLY_REFUNDED,
            PaymentStatus.REFUND_PENDING to PaymentStatus.REFUNDED,
            PaymentStatus.REFUND_PENDING to PaymentStatus.FAILED,
            PaymentStatus.PARTIALLY_REFUNDED to PaymentStatus.PARTIALLY_REFUNDED,
            PaymentStatus.PARTIALLY_REFUNDED to PaymentStatus.REFUNDED,
        )

        validTransitions.forEach { (from, to) ->
            assertTrue(runCatching { assertPaymentTransition(from, to) }.isSuccess, "$from -> $to")
        }
    }

    @Test
    fun `invalid payment transitions are rejected`() {
        listOf(
            PaymentStatus.CREATED to PaymentStatus.CAPTURED,
            PaymentStatus.PROCESSING to PaymentStatus.REFUNDED,
            PaymentStatus.AUTHORIZED to PaymentStatus.REFUNDED,
            PaymentStatus.REFUNDED to PaymentStatus.CAPTURED,
            PaymentStatus.CANCELLED to PaymentStatus.PROCESSING,
            PaymentStatus.EXPIRED to PaymentStatus.PROCESSING,
        ).forEach { (from, to) ->
            assertFailsWith<IllegalStateException> { assertPaymentTransition(from, to) }
        }
    }
}
