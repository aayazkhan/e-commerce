package com.ecommerce.checkout

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckoutSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `checkout request and totals preserve defaults and optional values`() {
        val request = CheckoutRequest("cart-1", "ship-1", "bill-1", CheckoutShippingMethod.EXPRESS, "token", "COD", "INR", "SAVE10")
        val totals = CheckoutTotals(10000, 500, 250, 100, 1750, 11100, "INR")
        assertEquals(request, json.decodeFromString<CheckoutRequest>(json.encodeToString(request)))
        assertEquals(totals, json.decodeFromString<CheckoutTotals>(json.encodeToString(totals)))
        assertEquals(CheckoutRequest(shippingAddressId = "ship", paymentMethodToken = "token"), json.decodeFromString(json.encodeToString(CheckoutRequest(shippingAddressId = "ship", paymentMethodToken = "token"))))
        val sparse = CheckoutRequest(shippingAddressId = "ship", paymentMethodToken = "token")
        listOf(
            sparse.copy(cartId = "cart-1"),
            sparse.copy(billingAddressId = "bill-1"),
            sparse.copy(shippingMethod = CheckoutShippingMethod.EXPRESS),
            sparse.copy(paymentProvider = "COD"),
            sparse.copy(currency = "USD"),
            sparse.copy(couponCode = "SAVE10"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(CheckoutRequest.serializer(), compactJson.encodeToString(CheckoutRequest.serializer(), value)))
        }
    }

    @Test
    fun `checkout response covers payment totals and error branches`() {
        val response = CheckoutResponse(
            checkoutId = "checkout-1",
            status = CheckoutStatus.PAYMENT_ACTION_REQUIRED,
            currentStep = CheckoutStep.CREATE_PAYMENT,
            orderId = "order-1",
            reservationId = "reservation-1",
            promotionRedemptionId = "redemption-1",
            payment = CheckoutPayment("payment-1", "REQUIRES_ACTION", "secret"),
            totals = CheckoutTotals(100, 0, 0, 0, 18, 118, "INR"),
            error = "action required",
            createdAt = "2026-08-20T10:00:00Z",
            updatedAt = "2026-08-20T10:01:00Z",
        )
        val validation = CheckoutValidationResponse(false, response.totals, listOf("coupon expired"))
        assertEquals(response, json.decodeFromString<CheckoutResponse>(json.encodeToString(response)))
        assertEquals(validation, json.decodeFromString<CheckoutValidationResponse>(json.encodeToString(validation)))
    }

    @Test
    fun `compact checkout serialization preserves default and nullable wire values`() {
        val request = CheckoutRequest(shippingAddressId = "address-1", paymentMethodToken = "token")
        val payment = CheckoutPayment("payment-1", "AUTHORIZED")
        val totals = CheckoutTotals(1_000, 0, 0, 0, 180, 1_180, "INR")
        val response = CheckoutResponse("checkout-1", CheckoutStatus.CREATED, CheckoutStep.VALIDATE_CART, createdAt = "2026-08-20T00:00:00Z", updatedAt = "2026-08-20T00:00:00Z")
        val validation = CheckoutValidationResponse(true, totals)

        assertEquals(request, compactJson.decodeFromString(CheckoutRequest.serializer(), compactJson.encodeToString(CheckoutRequest.serializer(), request)))
        assertEquals(payment, compactJson.decodeFromString(CheckoutPayment.serializer(), compactJson.encodeToString(CheckoutPayment.serializer(), payment)))
        assertEquals(totals, compactJson.decodeFromString(CheckoutTotals.serializer(), compactJson.encodeToString(CheckoutTotals.serializer(), totals)))
        assertEquals(response, compactJson.decodeFromString(CheckoutResponse.serializer(), compactJson.encodeToString(CheckoutResponse.serializer(), response)))
        assertEquals(validation, compactJson.decodeFromString(CheckoutValidationResponse.serializer(), compactJson.encodeToString(CheckoutValidationResponse.serializer(), validation)))
        listOf(
            response.copy(orderId = "order-1"),
            response.copy(reservationId = "reservation-1"),
            response.copy(promotionRedemptionId = "redemption-1"),
            response.copy(payment = payment),
            response.copy(totals = totals),
            response.copy(error = "failed"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(CheckoutResponse.serializer(), compactJson.encodeToString(CheckoutResponse.serializer(), value)))
        }
    }

    @Test
    fun `public checkout wire models preserve nullable identifiers and optional provider fields`() {
        val reservation = ReservationWire(
            "reservation-1", "key-1", "user-1", null, "order-1", "RESERVED",
            "2026-08-21T00:15:00Z", "2026-08-20T00:00:00Z", "2026-08-20T00:01:00Z",
            listOf(ReservationItemWire("variant-1", "warehouse-1", 2)),
        )
        val orderItem = OrderItemWire(
            "product-1", "variant-1", "Shoe", null, null, 1, 500, 0, 0, 500,
            "INR", "price-1", emptyMap(),
        )
        val redemption = RedemptionWire("redemption-1", "promotion-1", null, "user-1", null, 0, "INR", "RESERVED")

        assertEquals(reservation, json.decodeFromString<ReservationWire>(json.encodeToString(reservation)))
        assertEquals(orderItem, compactJson.decodeFromString<OrderItemWire>(compactJson.encodeToString(orderItem)))
        assertEquals(redemption, compactJson.decodeFromString<RedemptionWire>(compactJson.encodeToString(redemption)))
        listOf(
            orderItem,
            orderItem.copy(sku = "SKU-1"),
            orderItem.copy(sellerId = "seller-1"),
            orderItem.copy(attributes = mapOf("size" to "9")),
            orderItem.copy(sku = "SKU-1", sellerId = "seller-1", attributes = mapOf("size" to "9")),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(OrderItemWire.serializer(), compactJson.encodeToString(OrderItemWire.serializer(), value)))
        }
        assertEquals(CheckoutPayment("payment-1", "AUTHORIZED"), compactJson.decodeFromString<CheckoutPayment>("{\"id\":\"payment-1\",\"status\":\"AUTHORIZED\"}"))
        assertEquals(CheckoutRequest(shippingAddressId = "ship-1", paymentMethodToken = "token"), compactJson.decodeFromString<CheckoutRequest>("{\"shippingAddressId\":\"ship-1\",\"paymentMethodToken\":\"token\"}"))
    }

    @Test
    fun `checkout request serialization accepts every shipping method and response lifecycle state`() {
        CheckoutShippingMethod.entries.forEach { method ->
            val request = CheckoutRequest(shippingAddressId = "ship-1", shippingMethod = method, paymentMethodToken = "token")
            assertEquals(method, compactJson.decodeFromString<CheckoutRequest>(compactJson.encodeToString(request)).shippingMethod)
        }
        CheckoutStatus.entries.forEach { status ->
            val response = CheckoutResponse(
                "checkout-1", status, CheckoutStep.COMPLETE,
                createdAt = "2026-08-20T00:00:00Z", updatedAt = "2026-08-20T00:01:00Z",
            )
            assertEquals(status, compactJson.decodeFromString<CheckoutResponse>(compactJson.encodeToString(response)).status)
        }
        CheckoutStep.entries.forEach { step ->
            val response = CheckoutResponse(
                "checkout-1", CheckoutStatus.CREATED, step,
                createdAt = "2026-08-20T00:00:00Z", updatedAt = "2026-08-20T00:01:00Z",
            )
            assertEquals(step, compactJson.decodeFromString<CheckoutResponse>(compactJson.encodeToString(response)).currentStep)
        }
    }
}
