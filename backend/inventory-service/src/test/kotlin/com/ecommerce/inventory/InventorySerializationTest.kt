package com.ecommerce.inventory

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class InventorySerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `inventory and reservation models preserve quantities and ownership`() {
        val item = InventoryItem("item-1", "warehouse-1", "product-1", "variant-1", 100, 20, 80, 10, 4, "2026-08-20T10:00:00Z")
        val reservationItems = listOf(ReservationItem("variant-1", "warehouse-1", 2))
        val reservation = Reservation("reservation-1", "key-1", "actor-1", "cart-1", "order-1", ReservationStatus.ACTIVE, "2026-08-20T11:00:00Z", "2026-08-20T10:00:00Z", "2026-08-20T10:00:00Z", reservationItems)
        assertEquals(item, json.decodeFromString<InventoryItem>(json.encodeToString(item)))
        ReservationStatus.entries.forEach { status ->
            val copy = reservation.copy(status = status)
            assertEquals(copy, json.decodeFromString<Reservation>(json.encodeToString(copy)))
        }
    }

    @Test
    fun `inventory event movement and reservation payloads round trip`() {
        val reservation = ReservationEventPayload("reservation-1", "COMMITTED", listOf(ReservationItem("variant-1", "warehouse-1", 2)))
        val inventory = InventoryEventPayload("item-1", "variant-1", "warehouse-1", 80, 10)
        val movement = InventoryMovement("movement-1", "RESTOCK", 20, 100, 20, 80, "purchase-1", "actor-1", "2026-08-20T10:00:00Z")
        assertEquals(reservation, json.decodeFromString<ReservationEventPayload>(json.encodeToString(reservation)))
        assertEquals(inventory, json.decodeFromString<InventoryEventPayload>(json.encodeToString(inventory)))
        assertEquals(movement, json.decodeFromString<InventoryMovement>(json.encodeToString(movement)))
    }

    @Test
    fun `inventory HTTP requests preserve optional warehouse and reservation ownership fields`() {
        val sparseItem = InventoryItemRequest(productId = "product-1", variantId = "variant-1", onHand = 10)
        val completeItem = InventoryItemRequest("warehouse-1", "WH1", "Main", "product-1", "variant-1", 10, 2)
        val sparseReservation = ReservationRequest("reservation-key", items = listOf(ReservationItem("variant-1", "warehouse-1", 2)))
        val completeReservation = ReservationRequest(
            "reservation-key",
            cartId = "cart-1",
            orderId = "order-1",
            items = listOf(ReservationItem("variant-1", "warehouse-1", 2)),
            ttlSeconds = 3_600,
        )

        assertEquals(sparseItem, compactJson.decodeFromString<InventoryItemRequest>(compactJson.encodeToString(sparseItem)))
        assertEquals(completeItem, json.decodeFromString<InventoryItemRequest>(json.encodeToString(completeItem)))
        assertEquals(sparseReservation, compactJson.decodeFromString<ReservationRequest>(compactJson.encodeToString(sparseReservation)))
        assertEquals(completeReservation, json.decodeFromString<ReservationRequest>(json.encodeToString(completeReservation)))
        listOf(
            sparseItem.copy(warehouseId = "warehouse-1"),
            sparseItem.copy(warehouseCode = "WH1"),
            sparseItem.copy(warehouseName = "Main"),
            sparseItem.copy(lowStockThreshold = 2),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString<InventoryItemRequest>(compactJson.encodeToString(value)))
        }
        listOf(
            sparseReservation.copy(cartId = "cart-1"),
            sparseReservation.copy(orderId = "order-1"),
            sparseReservation.copy(ttlSeconds = 3_600),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString<ReservationRequest>(compactJson.encodeToString(value)))
        }
    }

    @Test
    fun `repository input contracts preserve nullable ownership and warehouse fields`() {
        val reservation = ReservationInput(
            reservationKey = "reservation-key",
            actorId = "actor-1",
            cartId = null,
            orderId = null,
            items = listOf(ReservationItem("variant-1", "warehouse-1", 2)),
            ttlSeconds = 60,
        )
        val item = InventoryItemInput(
            warehouseId = "warehouse-1",
            warehouseCode = null,
            warehouseName = null,
            productId = "product-1",
            variantId = "variant-1",
            onHand = 10,
            lowStockThreshold = 2,
        )

        assertEquals(reservation, compactJson.decodeFromString<ReservationInput>(compactJson.encodeToString(reservation)))
        assertEquals(item, compactJson.decodeFromString<InventoryItemInput>(compactJson.encodeToString(item)))
        assertEquals(
            item.copy(warehouseCode = "WH1", warehouseName = "Main"),
            json.decodeFromString<InventoryItemInput>(json.encodeToString(item.copy(warehouseCode = "WH1", warehouseName = "Main"))),
        )
    }

    private val compactJson = Json { explicitNulls = false; encodeDefaults = false }
}
