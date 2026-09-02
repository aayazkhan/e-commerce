package com.ecommerce.cart

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CartDomainTest {
    @Test fun actorCannotMixAuthenticatedAndGuestOwnership() {
        assertFailsWith<IllegalArgumentException> { CartActor(userId = "user_1", guestToken = "guest-token") }
        assertFailsWith<IllegalArgumentException> { CartActor() }
        CartActor(userId = "user_1")
        CartActor(guestToken = "guest-token")
    }
}
