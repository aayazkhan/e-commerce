package com.ecommerce.wishlist

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WishlistEventSerializationTest {
    private val compact = Json { encodeDefaults = false; explicitNulls = false }
    private val explicit = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `wishlist event preserves every optional payload combination`() {
        val values = listOf(
            WishlistEvent("user-1", null),
            WishlistEvent("user-1", "variant-1"),
            WishlistEvent("user-1", "variant-1", productId = "product-1"),
            WishlistEvent("user-1", "variant-1", itemId = "item-1"),
            WishlistEvent("user-1", "variant-1", productId = "product-1", itemId = "item-1"),
        )

        values.forEach { value ->
            assertEquals(value, compact.decodeFromString<WishlistEvent>(compact.encodeToString(value)))
            assertEquals(value, explicit.decodeFromString<WishlistEvent>(explicit.encodeToString(value)))
        }
    }

    @Test
    fun `wishlist event decoder rejects missing identity but accepts explicit nullable variant`() {
        assertFailsWith<SerializationException> {
            compact.decodeFromString<WishlistEvent>("{}")
        }
        val event = compact.decodeFromString<WishlistEvent>("{\"userId\":\"user-1\",\"variantId\":null}")
        assertEquals("user-1", event.userId)
        assertEquals(null, event.variantId)
        assertEquals(null, event.productId)
        assertEquals(null, event.itemId)
        listOf(
            PriceDto("INR", 1_000),
            PriceDto("INR", 1_000, 900),
        ).forEach { value ->
            assertEquals(value, compact.decodeFromString(PriceDto.serializer(), compact.encodeToString(PriceDto.serializer(), value)))
        }
    }
}
