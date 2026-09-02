package com.ecommerce.payment

import kotlin.test.Test
import kotlin.test.assertFails

class PaymentDomainTest {
    @Test fun `payment cannot refund before capture`() { assertFails { assertPaymentTransition(PaymentStatus.PROCESSING, PaymentStatus.REFUNDED) } }
    @Test fun `payment allows capture`() { assertPaymentTransition(PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED) }
}
