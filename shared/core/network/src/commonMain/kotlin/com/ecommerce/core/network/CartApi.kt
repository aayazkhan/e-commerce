package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

/** Mirrors backend/cart-service's CartDomain.kt exactly. */
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
    val userId: String? = null,
    val currency: String,
    val version: Long,
    val items: List<CartItem>,
    val guestToken: String? = null,
    val warnings: List<CartWarning> = emptyList(),
)

@Serializable
data class CartWarning(val variantId: String, val code: String, val message: String, val currentUnitPriceMinor: Long? = null)

@Serializable
private data class AddItemRequest(val productId: String, val variantId: String, val quantity: Int)

@Serializable
private data class UpdateItemRequest(val quantity: Int)

@Serializable
private data class MergeRequest(val guestToken: String)

/**
 * cart-service supports both logged-in and guest carts via an `X-Guest-Token` header. Every
 * mutating call also needs a fresh `Idempotency-Key` -- see cart-service's Application.kt.
 */
class CartApi(private val client: ApiClient) {
    suspend fun get(guestToken: String?): ApiResult<CartResponse> = client.get("/api/v1/cart", guestHeaders(guestToken))

    suspend fun addItem(productId: String, variantId: String, quantity: Int, guestToken: String?, idempotencyKey: String): ApiResult<CartResponse> =
        client.post("/api/v1/cart/items", AddItemRequest(productId, variantId, quantity), guestHeaders(guestToken) + idempotencyHeader(idempotencyKey))

    suspend fun updateItem(itemId: String, quantity: Int, guestToken: String?, idempotencyKey: String): ApiResult<CartResponse> =
        client.patch("/api/v1/cart/items/$itemId", UpdateItemRequest(quantity), guestHeaders(guestToken) + idempotencyHeader(idempotencyKey))

    suspend fun removeItem(itemId: String, guestToken: String?, idempotencyKey: String): ApiResult<CartResponse> =
        client.delete("/api/v1/cart/items/$itemId", guestHeaders(guestToken) + idempotencyHeader(idempotencyKey))

    /** Called once right after login, when the browsing session had a guest cart -- folds it
     * into the now-authenticated user's cart. Requires the caller's bearer token to already be set. */
    suspend fun merge(guestToken: String): ApiResult<CartResponse> =
        client.post("/api/v1/cart/merge", MergeRequest(guestToken))

    private fun guestHeaders(guestToken: String?) = guestToken?.let { mapOf("X-Guest-Token" to it) } ?: emptyMap()
    private fun idempotencyHeader(key: String) = mapOf("Idempotency-Key" to key)
}
