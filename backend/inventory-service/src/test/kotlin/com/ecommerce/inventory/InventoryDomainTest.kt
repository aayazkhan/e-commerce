package com.ecommerce.inventory

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class InventoryDomainTest {
    @Test fun quantityMustBePositive() {
        assertEquals(Unit, validateQuantity(1))
        assertFailsWith<IllegalArgumentException> { validateQuantity(0) }
        assertFailsWith<IllegalArgumentException> { validateQuantity(-1) }
    }
}
