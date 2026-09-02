package com.ecommerce.wishlist

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WishlistDomainTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `wishlist page preserves availability and cursor state`() {
        val item = WishlistItemView("wish-1", "product-1", "variant-1", "2026-08-20T00:00:00Z", "IN_STOCK", 4, 1_999, "INR")
        val page = WishlistPage(listOf(item), "next", true)

        assertEquals(page, json.decodeFromString<WishlistPage>(json.encodeToString(page)))
        assertEquals(WishlistRecord("wish-1", "user-1", "product-1", "variant-1", item.createdAt).productId, "product-1")
    }
}
