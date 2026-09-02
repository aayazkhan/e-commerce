package com.ecommerce.order

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrderTransitionMatrixTest {
    @Test
    fun `every order status is idempotent`() {
        OrderStatus.entries.forEach { status ->
            assertTrue(runCatching { assertOrderTransition(status, status) }.isSuccess)
        }
    }

    @Test
    fun `every documented order transition is accepted`() {
        val validTransitions = listOf(
            OrderStatus.CREATED to OrderStatus.VALIDATING,
            OrderStatus.CREATED to OrderStatus.INVENTORY_RESERVED,
            OrderStatus.CREATED to OrderStatus.CANCELLED,
            OrderStatus.CREATED to OrderStatus.FAILED,
            OrderStatus.VALIDATING to OrderStatus.INVENTORY_RESERVED,
            OrderStatus.VALIDATING to OrderStatus.CANCELLED,
            OrderStatus.VALIDATING to OrderStatus.FAILED,
            OrderStatus.INVENTORY_RESERVED to OrderStatus.PAYMENT_PENDING,
            OrderStatus.INVENTORY_RESERVED to OrderStatus.CANCELLED,
            OrderStatus.INVENTORY_RESERVED to OrderStatus.FAILED,
            OrderStatus.PAYMENT_PENDING to OrderStatus.PAYMENT_PROCESSING,
            OrderStatus.PAYMENT_PENDING to OrderStatus.CANCELLED,
            OrderStatus.PAYMENT_PENDING to OrderStatus.FAILED,
            OrderStatus.PAYMENT_PROCESSING to OrderStatus.PAID,
            OrderStatus.PAYMENT_PROCESSING to OrderStatus.FAILED,
            OrderStatus.PAYMENT_PROCESSING to OrderStatus.CANCEL_REQUESTED,
            OrderStatus.PAID to OrderStatus.CONFIRMED,
            OrderStatus.PAID to OrderStatus.CANCEL_REQUESTED,
            OrderStatus.PAID to OrderStatus.REFUND_PENDING,
            OrderStatus.PAID to OrderStatus.FAILED,
            OrderStatus.CONFIRMED to OrderStatus.PROCESSING,
            OrderStatus.CONFIRMED to OrderStatus.CANCEL_REQUESTED,
            OrderStatus.CONFIRMED to OrderStatus.REFUND_PENDING,
            OrderStatus.PROCESSING to OrderStatus.SHIPPED,
            OrderStatus.PROCESSING to OrderStatus.CANCEL_REQUESTED,
            OrderStatus.SHIPPED to OrderStatus.OUT_FOR_DELIVERY,
            OrderStatus.SHIPPED to OrderStatus.RETURN_REQUESTED,
            OrderStatus.OUT_FOR_DELIVERY to OrderStatus.DELIVERED,
            OrderStatus.OUT_FOR_DELIVERY to OrderStatus.RETURN_REQUESTED,
            OrderStatus.DELIVERED to OrderStatus.RETURN_REQUESTED,
            OrderStatus.CANCEL_REQUESTED to OrderStatus.CANCELLED,
            OrderStatus.CANCEL_REQUESTED to OrderStatus.FAILED,
            OrderStatus.RETURN_REQUESTED to OrderStatus.RETURN_APPROVED,
            OrderStatus.RETURN_REQUESTED to OrderStatus.CANCELLED,
            OrderStatus.RETURN_APPROVED to OrderStatus.RETURN_PICKUP,
            OrderStatus.RETURN_PICKUP to OrderStatus.RETURNED,
            OrderStatus.RETURNED to OrderStatus.REFUND_PENDING,
            OrderStatus.REFUND_PENDING to OrderStatus.REFUNDED,
            OrderStatus.REFUND_PENDING to OrderStatus.FAILED,
        )

        validTransitions.forEach { (from, to) ->
            assertTrue(runCatching { assertOrderTransition(from, to) }.isSuccess, "$from -> $to")
        }
    }

    @Test
    fun `invalid order transitions are rejected`() {
        listOf(
            OrderStatus.CREATED to OrderStatus.PAID,
            OrderStatus.INVENTORY_RESERVED to OrderStatus.CONFIRMED,
            OrderStatus.PAID to OrderStatus.DELIVERED,
            OrderStatus.DELIVERED to OrderStatus.PROCESSING,
            OrderStatus.CANCELLED to OrderStatus.CREATED,
            OrderStatus.REFUNDED to OrderStatus.CREATED,
        ).forEach { (from, to) ->
            assertFailsWith<IllegalStateException> { assertOrderTransition(from, to) }
        }
    }
}
