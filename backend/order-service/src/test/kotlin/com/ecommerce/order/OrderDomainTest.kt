package com.ecommerce.order

import kotlin.test.Test
import kotlin.test.assertFails

class OrderDomainTest {
    @Test fun `order matrix rejects skipping payment`() { assertFails { assertOrderTransition(OrderStatus.INVENTORY_RESERVED, OrderStatus.CONFIRMED) } }
    @Test fun `order matrix accepts delivery return`() { assertOrderTransition(OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED) }
}
