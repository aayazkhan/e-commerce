package com.ecommerce.wishlist

import kotlinx.serialization.Serializable

data class WishlistRecord(val id: String, val userId: String, val productId: String, val variantId: String, val createdAt: String)

@Serializable
data class WishlistItemView(
    val id: String,
    val productId: String,
    val variantId: String,
    val createdAt: String,
    val availability: String,
    val availableQuantity: Long,
    val unitPriceMinor: Long?,
    val currency: String?,
)

@Serializable
data class WishlistPage(val items: List<WishlistItemView>, val nextCursor: String?, val hasMore: Boolean)
