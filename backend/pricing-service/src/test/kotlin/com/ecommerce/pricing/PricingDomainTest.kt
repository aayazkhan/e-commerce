package com.ecommerce.pricing

import kotlin.test.Test
import kotlin.test.assertEquals

class PricingDomainTest {
    @Test fun `tax rounds deterministically in minor units`() { assertEquals(18L, calculateTaxMinor(199L, 900)); assertEquals(0L, calculateTaxMinor(1L, 1)) }
}
