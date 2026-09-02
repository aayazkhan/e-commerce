package com.ecommerce.pricing

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PricingCalculationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `tax uses minor units and half-up rounding`() {
        assertEquals(18L, calculateTaxMinor(199L, 900))
        assertEquals(1L, calculateTaxMinor(100L, 50))
        assertEquals(0L, calculateTaxMinor(0L, 9_999))
        assertEquals(1L, calculateTaxMinor(1L, 5_000))
    }

    @Test
    fun `price prefers sale amount and survives serialization`() {
        val sale = Price("price-1", "product-1", "variant-1", "seller-1", "INR", "IN", "DEFAULT", 2_000, 1_500, 900, "2026-08-20T00:00:00Z", null, 2, "seller-1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val base = sale.copy(saleMinor = null)

        assertEquals(1_500L, sale.unitMinor)
        assertEquals(2_000L, base.unitMinor)
        assertEquals(sale, json.decodeFromString<Price>(json.encodeToString(sale)))
    }
}
