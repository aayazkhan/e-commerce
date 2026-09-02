package com.ecommerce.inventory

import kotlinx.serialization.Serializable

@Serializable
enum class ReservationStatus { ACTIVE, RELEASED, EXPIRED, COMMITTED, CANCELLED }

@Serializable
enum class AdjustmentType { RESTOCK, DAMAGE, LOSS, CORRECTION, RETURN, TRANSFER }

@Serializable
data class InventoryItem(val id: String, val warehouseId: String, val productId: String, val variantId: String, val onHand: Long, val reserved: Long, val available: Long, val lowStockThreshold: Long, val version: Long, val updatedAt: String)

@Serializable
data class ReservationItem(val variantId: String, val warehouseId: String, val quantity: Long)

@Serializable
data class Reservation(val id: String, val reservationKey: String, val actorId: String, val cartId: String?, val orderId: String?, val status: ReservationStatus, val expiresAt: String, val createdAt: String, val updatedAt: String, val items: List<ReservationItem>)

@Serializable
data class ReservationEventPayload(val reservationId: String, val status: String, val items: List<ReservationItem>)

@Serializable
data class InventoryEventPayload(val inventoryItemId: String, val variantId: String, val warehouseId: String, val available: Long, val threshold: Long)

@Serializable
data class InventoryMovement(val id: String, val type: String, val quantityDelta: Long, val onHand: Long, val reserved: Long, val available: Long, val referenceId: String?, val actorId: String, val createdAt: String)

fun validateQuantity(quantity: Long) { require(quantity > 0) { "Quantity must be positive" } }
