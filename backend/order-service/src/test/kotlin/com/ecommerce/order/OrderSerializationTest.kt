package com.ecommerce.order

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    private val item = OrderItemSnapshot("product-1", "variant-1", "Shoes", "SKU-1", "seller-1", 2, 2500, 450, 100, 5350, "INR", "price-v4", mapOf("size" to "9"))
    private val address = AddressSnapshot("address-1", "Ayyaz Khan", "+919999999999", "1 Main Road", "Floor 2", "Mumbai", "MH", "400001", "IN")

    @Test
    fun `order snapshots and requests preserve optional values`() {
        val promotion = PromotionSnapshot("promo-1", "SAVE10", 100, mapOf("variant-1" to 100L))
        val request = OrderCreateRequest("checkout-1", "reservation-1", listOf(item), address, address, 5000, 100, 100, 0, 450, 5250, "INR", promotion, OrderStatus.PAYMENT_PENDING)
        val response = OrderResponse("order-1", "user-1", "checkout-1", "reservation-1", OrderStatus.PAID, 5000, 100, 100, 0, 450, 5250, "INR", listOf(item), address, address, promotion, 3, "2026-08-20T10:00:00Z", "2026-08-20T10:05:00Z")
        assertEquals(request, json.decodeFromString<OrderCreateRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<OrderResponse>(json.encodeToString(response)))
    }

    @Test
    fun `status and return models round trip`() {
        OrderStatus.entries.forEach { status ->
            assertEquals(OrderStatusRequest(status), json.decodeFromString<OrderStatusRequest>(json.encodeToString(OrderStatusRequest(status))))
        }
        val request = ReturnRequest("Wrong size", listOf(ReturnItemRequest("variant-1", 1)))
        val response = ReturnResponse("return-1", "order-1", "REQUESTED", "Wrong size", request.items, "2026-08-20T10:00:00Z")
        assertEquals(request, json.decodeFromString<ReturnRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<ReturnResponse>(json.encodeToString(response)))
    }

    @Test
    fun `compact order serialization preserves defaults and nullable snapshots`() {
        val compactItem = OrderItemSnapshot("product-1", "variant-1", "Shoes", quantity = 1, unitPriceMinor = 100, taxMinor = 0, discountMinor = 0, lineTotalMinor = 100, currency = "INR")
        val compactAddress = AddressSnapshot("address-1", "Customer", "phone", "Line 1", city = "Pune", state = "MH", postalCode = "411001", country = "IN")
        val promotion = PromotionSnapshot()
        val request = OrderCreateRequest("checkout-1", items = listOf(compactItem), shippingAddress = compactAddress, billingAddress = compactAddress, subtotalMinor = 100, itemDiscountMinor = 0, promotionDiscountMinor = 0, shippingMinor = 0, taxMinor = 0, totalMinor = 100, currency = "INR")
        val status = OrderStatusRequest(OrderStatus.CREATED)
        val returnItem = ReturnItemRequest("variant-1", 1)
        val returnRequest = ReturnRequest("Wrong size", listOf(returnItem))

        assertEquals(compactItem, compactJson.decodeFromString(OrderItemSnapshot.serializer(), compactJson.encodeToString(OrderItemSnapshot.serializer(), compactItem)))
        assertEquals(compactAddress, compactJson.decodeFromString(AddressSnapshot.serializer(), compactJson.encodeToString(AddressSnapshot.serializer(), compactAddress)))
        assertEquals(promotion, compactJson.decodeFromString(PromotionSnapshot.serializer(), compactJson.encodeToString(PromotionSnapshot.serializer(), promotion)))
        assertEquals(request, compactJson.decodeFromString(OrderCreateRequest.serializer(), compactJson.encodeToString(OrderCreateRequest.serializer(), request)))
        assertEquals(status, compactJson.decodeFromString(OrderStatusRequest.serializer(), compactJson.encodeToString(OrderStatusRequest.serializer(), status)))
        assertEquals(returnRequest, compactJson.decodeFromString(ReturnRequest.serializer(), compactJson.encodeToString(ReturnRequest.serializer(), returnRequest)))
        listOf(CancelBody(), CancelBody("changed-my-mind")).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(CancelBody.serializer(), compactJson.encodeToString(CancelBody.serializer(), value)))
        }
        listOf(
            compactItem.copy(sku = "SKU-1"),
            compactItem.copy(sellerId = "seller-1"),
            compactItem.copy(priceVersion = "price-v1"),
            compactItem.copy(attributes = mapOf("size" to "9")),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(OrderItemSnapshot.serializer(), compactJson.encodeToString(OrderItemSnapshot.serializer(), value)))
        }
        listOf(
            promotion.copy(promotionId = "promo-1"),
            promotion.copy(couponCode = "SAVE10"),
            promotion.copy(discountMinor = 100),
            promotion.copy(allocation = mapOf("variant-1" to 100L)),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(PromotionSnapshot.serializer(), compactJson.encodeToString(PromotionSnapshot.serializer(), value)))
        }

        val compactResponse = OrderResponse(
            id = "order-1", userId = "user-1", checkoutId = "checkout-1", reservationId = null,
            status = OrderStatus.CREATED, subtotalMinor = 100, itemDiscountMinor = 0, promotionDiscountMinor = 0,
            shippingMinor = 0, taxMinor = 0, totalMinor = 100, currency = "INR", items = listOf(compactItem),
            shippingAddress = compactAddress, billingAddress = compactAddress, promotion = null, version = 1,
            createdAt = "created", updatedAt = "updated",
        )
        assertEquals(compactResponse, compactJson.decodeFromString(OrderResponse.serializer(), compactJson.encodeToString(OrderResponse.serializer(), compactResponse)))
    }
}
