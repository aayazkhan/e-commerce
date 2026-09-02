package com.ecommerce.promotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromotionDomainTest {
    @Test fun percentageUsesMinorUnitsAndBasisPoints() {
        assertEquals(1250, percentageDiscountMinor(10_000, 1250))
        assertEquals(0, percentageDiscountMinor(10_000, 0))
        assertFailsWith<IllegalArgumentException> { percentageDiscountMinor(10_000, 10_001) }
    }

    @Test fun lineTotalIsDerivedFromQuantity() {
        assertEquals(3_000, PromotionLine("p", "v", quantity = 3, unitPriceMinor = 1_000).lineTotalMinor)
    }
}
