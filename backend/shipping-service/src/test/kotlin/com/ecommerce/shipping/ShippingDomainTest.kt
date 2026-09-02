package com.ecommerce.shipping

import kotlin.test.Test
import kotlin.test.assertFails

class ShippingDomainTest {
    @Test fun `shipment rejects delivered to transit`() { assertFails { assertShipmentTransition(ShipmentStatus.DELIVERED, ShipmentStatus.IN_TRANSIT) } }
    @Test fun `shipment allows delivery return request`() { assertShipmentTransition(ShipmentStatus.DELIVERED, ShipmentStatus.RETURN_REQUESTED) }
}
