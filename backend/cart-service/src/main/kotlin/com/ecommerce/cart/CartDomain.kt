package com.ecommerce.cart

import kotlinx.serialization.Serializable

@Serializable
data class CartItem(
    val id: String,
    val productId: String,
    val variantId: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val currency: String,
    val priceVersion: String,
    val addedAt: String,
    val updatedAt: String,
)

@Serializable
data class CartResponse(
    val id: String,
    val userId: String?,
    val currency: String,
    val version: Long,
    val items: List<CartItem>,
    val guestToken: String? = null,
    val warnings: List<CartWarning> = emptyList(),
)

@Serializable
data class CartWarning(
    val variantId: String,
    val code: String,
    val message: String,
    val currentUnitPriceMinor: Long? = null,
)

@Serializable
data class CartValidation(val cart: CartResponse, val valid: Boolean, val warnings: List<CartWarning>)
