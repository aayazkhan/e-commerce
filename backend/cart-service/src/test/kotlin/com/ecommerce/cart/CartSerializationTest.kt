package com.ecommerce.cart

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CartSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `cart response preserves authenticated and guest fields`() {
        val item = CartItem("item-1", "product-1", "variant-1", 2, 1250, "INR", "v3", "2026-08-20T10:00:00Z", "2026-08-20T10:01:00Z")
        val warning = CartWarning("variant-1", "PRICE_CHANGED", "Price changed", 1300)
        val cart = CartResponse("cart-1", "user-1", "INR", 7, listOf(item), "guest-1", listOf(warning))
        assertEquals(cart, json.decodeFromString<CartResponse>(json.encodeToString(cart)))
        assertEquals(CartResponse("guest-cart", null, "INR", 1, emptyList()), json.decodeFromString(json.encodeToString(CartResponse("guest-cart", null, "INR", 1, emptyList()))))
    }

    @Test
    fun `cart validation preserves validity and warnings`() {
        val validation = CartValidation(CartResponse("cart", "user", "INR", 2, emptyList()), false, listOf(CartWarning("v", "OUT_OF_STOCK", "Unavailable")))
        assertEquals(validation, json.decodeFromString<CartValidation>(json.encodeToString(validation)))
    }

    @Test
    fun `compact cart serialization preserves guest nulls empty warnings and price warning defaults`() {
        val item = CartItem("item-1", "product-1", "variant-1", 1, 100, "INR", "v1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val cart = CartResponse("cart-1", null, "INR", 1, listOf(item))
        val warning = CartWarning("variant-1", "OUT_OF_STOCK", "Unavailable")
        val validation = CartValidation(cart, false, listOf(warning))
        assertEquals(cart, compactJson.decodeFromString(CartResponse.serializer(), compactJson.encodeToString(CartResponse.serializer(), cart)))
        assertEquals(warning, compactJson.decodeFromString(CartWarning.serializer(), compactJson.encodeToString(CartWarning.serializer(), warning)))
        assertEquals(validation, compactJson.decodeFromString(CartValidation.serializer(), compactJson.encodeToString(CartValidation.serializer(), validation)))
        listOf(
            PriceDto("INR", 1_000, version = 1),
            PriceDto("INR", 1_000, 900, 1),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(PriceDto.serializer(), compactJson.encodeToString(PriceDto.serializer(), value)))
        }
    }
}
