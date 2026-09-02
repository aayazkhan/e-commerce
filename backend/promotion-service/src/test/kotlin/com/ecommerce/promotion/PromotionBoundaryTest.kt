package com.ecommerce.promotion

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromotionBoundaryTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `percentage discount covers zero full and fractional boundaries`() {
        assertEquals(0L, percentageDiscountMinor(10_000, 0))
        assertEquals(10_000L, percentageDiscountMinor(10_000, 10_000))
        assertEquals(1_250L, percentageDiscountMinor(10_000, 1_250))
        assertEquals(0L, percentageDiscountMinor(0, 10_000))
    }

    @Test
    fun `percentage discount rejects negative totals and rates outside basis points`() {
        assertFailsWith<IllegalArgumentException> { percentageDiscountMinor(-1, 100) }
        assertFailsWith<IllegalArgumentException> { percentageDiscountMinor(10_000, -1) }
        assertFailsWith<IllegalArgumentException> { percentageDiscountMinor(10_000, 10_001) }
    }

    @Test
    fun `promotion line calculates exact minor-unit total and serializes`() {
        val line = PromotionLine("product-1", "variant-1", "category-1", "seller-1", 3, 1_000)

        assertEquals(3_000L, line.lineTotalMinor)
        assertEquals(line, json.decodeFromString<PromotionLine>(json.encodeToString(line)))
    }
}
