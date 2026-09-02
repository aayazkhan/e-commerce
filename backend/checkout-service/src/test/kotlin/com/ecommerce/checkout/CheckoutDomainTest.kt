package com.ecommerce.checkout

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckoutDomainTest {
    @Test fun `checkout request defaults to server selected currency`() { assertEquals("INR", CheckoutRequest(shippingAddressId = "address", paymentMethodToken = "token").currency) }
}
