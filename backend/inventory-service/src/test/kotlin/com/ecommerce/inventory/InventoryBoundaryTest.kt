package com.ecommerce.inventory

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InventoryBoundaryTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `quantity requires a positive value`() {
        validateQuantity(1)
        validateQuantity(Long.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { validateQuantity(0) }
        assertFailsWith<IllegalArgumentException> { validateQuantity(-1) }
    }

    @Test
    fun `reservation payload preserves optional order and cart ownership`() {
        val item = ReservationItem("variant-1", "warehouse-1", 2)
        val reservation = Reservation("res-1", "key-1", "user-1", "cart-1", "order-1", ReservationStatus.ACTIVE, "2026-08-20T01:00:00Z", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z", listOf(item))

        assertEquals(reservation, json.decodeFromString<Reservation>(json.encodeToString(reservation)))
        assertEquals(item, reservation.items.single())
    }
}
