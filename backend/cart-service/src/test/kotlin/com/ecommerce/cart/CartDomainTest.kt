package com.ecommerce.cart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CartDomainTest {
    @Test fun actorCannotMixAuthenticatedAndGuestOwnership() {
        assertFailsWith<IllegalArgumentException> { CartActor(userId = "user_1", guestToken = "guest-token") }
        assertFailsWith<IllegalArgumentException> { CartActor() }
        CartActor(userId = "user_1")
        CartActor(guestToken = "guest-token")
    }

    @Test fun `cart item value equality covers every field`() {
        val item = CartItem("item-1", "product-1", "variant-1", 2, 100, "INR", "v1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val same = item.copy()
        assertEquals(item, same)
        assertEquals(item.hashCode(), same.hashCode())
        assertNotEquals(item, item.copy(id = "item-2"))
        assertNotEquals(item, item.copy(productId = "product-2"))
        assertNotEquals(item, item.copy(variantId = "variant-2"))
        assertNotEquals(item, item.copy(quantity = 3))
        assertNotEquals(item, item.copy(unitPriceMinor = 200))
        assertNotEquals(item, item.copy(currency = "USD"))
        assertNotEquals(item, item.copy(priceVersion = "v2"))
        assertNotEquals(item, item.copy(addedAt = "2026-08-21T00:00:00Z"))
        assertNotEquals(item, item.copy(updatedAt = "2026-08-21T00:00:00Z"))
        assertNotEquals<Any>(item, "not-an-item")
        assertTrue(item.toString().contains("item-1"))
    }

    @Test fun `cart response value equality covers nullable user id guest token and warnings`() {
        val item = CartItem("item-1", "product-1", "variant-1", 1, 100, "INR", "v1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val warning = CartWarning("variant-1", "PRICE_CHANGED", "changed", 150)
        val guest = CartResponse("cart-1", null, "INR", 1, listOf(item))
        val authenticated = CartResponse("cart-1", "user-1", "INR", 1, listOf(item))
        assertEquals(guest, guest.copy())
        assertNotEquals(guest, authenticated)
        assertNotEquals(guest, guest.copy(id = "cart-2"))
        assertNotEquals(guest, guest.copy(currency = "USD"))
        assertNotEquals(guest, guest.copy(version = 2))
        assertNotEquals(guest, guest.copy(items = emptyList()))
        assertNotEquals(guest, guest.copy(guestToken = "guest-token"))
        assertNotEquals(guest.copy(guestToken = "a"), guest.copy(guestToken = "b"))
        assertNotEquals(guest, guest.copy(warnings = listOf(warning)))
        assertNotEquals(guest.copy(warnings = listOf(warning)), guest.copy(warnings = emptyList()))
    }

    @Test fun `cart warning value equality covers optional current price`() {
        val withPrice = CartWarning("variant-1", "PRICE_CHANGED", "changed", 150)
        val withoutPrice = CartWarning("variant-1", "OUT_OF_STOCK", "unavailable")
        assertEquals(withPrice, withPrice.copy())
        assertNotEquals(withPrice, withoutPrice)
        assertNotEquals(withPrice, withPrice.copy(variantId = "variant-2"))
        assertNotEquals(withPrice, withPrice.copy(code = "OTHER"))
        assertNotEquals(withPrice, withPrice.copy(message = "different"))
        assertNotEquals(withPrice, withPrice.copy(currentUnitPriceMinor = 999))
        assertNotEquals(withPrice, withPrice.copy(currentUnitPriceMinor = null))
    }

    @Test fun `cart validation value equality covers validity and warning list`() {
        val cart = CartResponse("cart-1", null, "INR", 1, emptyList())
        val warning = CartWarning("variant-1", "OUT_OF_STOCK", "unavailable")
        val valid = CartValidation(cart, true, emptyList())
        val invalid = CartValidation(cart, false, listOf(warning))
        assertEquals(valid, valid.copy())
        assertNotEquals(valid, invalid)
        assertNotEquals(valid, valid.copy(cart = cart.copy(id = "cart-2")))
        assertNotEquals(valid, valid.copy(valid = false))
        assertNotEquals(valid, valid.copy(warnings = listOf(warning)))
    }
}
