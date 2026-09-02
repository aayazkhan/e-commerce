package com.ecommerce.shipping

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShippingTransitionMatrixTest {
    @Test
    fun `every shipment status is idempotent`() {
        ShipmentStatus.entries.forEach { status ->
            assertTrue(runCatching { assertShipmentTransition(status, status) }.isSuccess)
        }
    }

    @Test
    fun `every documented shipment transition is accepted`() {
        val validTransitions = listOf(
            ShipmentStatus.CREATED to ShipmentStatus.LABEL_PENDING,
            ShipmentStatus.CREATED to ShipmentStatus.CANCELLED,
            ShipmentStatus.LABEL_PENDING to ShipmentStatus.LABEL_CREATED,
            ShipmentStatus.LABEL_PENDING to ShipmentStatus.EXCEPTION,
            ShipmentStatus.LABEL_PENDING to ShipmentStatus.CANCELLED,
            ShipmentStatus.LABEL_CREATED to ShipmentStatus.PICKED_UP,
            ShipmentStatus.LABEL_CREATED to ShipmentStatus.CANCELLED,
            ShipmentStatus.PICKED_UP to ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.PICKED_UP to ShipmentStatus.EXCEPTION,
            ShipmentStatus.IN_TRANSIT to ShipmentStatus.OUT_FOR_DELIVERY,
            ShipmentStatus.IN_TRANSIT to ShipmentStatus.EXCEPTION,
            ShipmentStatus.OUT_FOR_DELIVERY to ShipmentStatus.DELIVERED,
            ShipmentStatus.OUT_FOR_DELIVERY to ShipmentStatus.EXCEPTION,
            ShipmentStatus.DELIVERED to ShipmentStatus.RETURN_REQUESTED,
            ShipmentStatus.RETURN_REQUESTED to ShipmentStatus.RETURN_PICKUP,
            ShipmentStatus.RETURN_PICKUP to ShipmentStatus.RETURNED,
        )

        validTransitions.forEach { (from, to) ->
            assertTrue(runCatching { assertShipmentTransition(from, to) }.isSuccess, "$from -> $to")
        }
    }

    @Test
    fun `invalid shipment transitions are rejected`() {
        listOf(
            ShipmentStatus.CREATED to ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.LABEL_CREATED to ShipmentStatus.DELIVERED,
            ShipmentStatus.DELIVERED to ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.RETURNED to ShipmentStatus.DELIVERED,
            ShipmentStatus.CANCELLED to ShipmentStatus.LABEL_PENDING,
            ShipmentStatus.EXCEPTION to ShipmentStatus.IN_TRANSIT,
        ).forEach { (from, to) ->
            assertFailsWith<IllegalStateException> { assertShipmentTransition(from, to) }
        }
    }
}
