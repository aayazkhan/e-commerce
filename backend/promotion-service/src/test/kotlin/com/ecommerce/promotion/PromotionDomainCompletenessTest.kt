package com.ecommerce.promotion

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromotionDomainCompletenessTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `all promotion enums and wire models round trip every meaningful state`() {
        PromotionType.entries.forEach { value -> assertEquals(value, json.decodeFromString(PromotionType.serializer(), json.encodeToString(PromotionType.serializer(), value))) }
        PromotionStatus.entries.forEach { value -> assertEquals(value, json.decodeFromString(PromotionStatus.serializer(), json.encodeToString(PromotionStatus.serializer(), value))) }
        StackPolicy.entries.forEach { value -> assertEquals(value, json.decodeFromString(StackPolicy.serializer(), json.encodeToString(StackPolicy.serializer(), value))) }
        RedemptionStatus.entries.forEach { value -> assertEquals(value, json.decodeFromString(RedemptionStatus.serializer(), json.encodeToString(RedemptionStatus.serializer(), value))) }

        val line = PromotionLine("product-1", "variant-1", "category-1", "seller-1", 2, 1_250)
        val request = PromotionCalculateRequest("USD", listOf(line), "SAVE10", "promotion-1", 499, "VIP")
        val quote = PromotionQuote("promotion-1", "SAVE10", "USD", 250, true, mapOf("variant-1" to 250), "eligible")
        val response = PromotionResponse("promotion-1", "Sale", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, "2026-08-20T00:00:00Z", "2026-12-31T23:59:59Z", "USD", 1_000, 5_000, 1_000, null, listOf("product-1"), listOf("category-1"), listOf("seller-1"), 100, 3, 2, StackPolicy.PRIORITY_BASED, 10, 4, listOf("VIP"))
        val redemption = RedemptionResponse("redemption-1", response.id, "SAVE10", "user-1", "order-1", 250, "USD", RedemptionStatus.RESERVED, quote, "2026-08-20T00:00:00Z")
        val promotionRequest = PromotionRequest("Sale", PromotionType.BUY_X_GET_Y, PromotionStatus.PAUSED, "2026-08-20T00:00:00Z", null, "INR", 1_000, null, null, null, 2, 1, listOf("p"), listOf("c"), listOf("s"), null, 1, StackPolicy.BEST_DISCOUNT_ONLY, 3, listOf("DEFAULT"))
        assertRoundTrip(line, PromotionLine.serializer())
        assertRoundTrip(request, PromotionCalculateRequest.serializer())
        assertRoundTrip(quote, PromotionQuote.serializer())
        assertRoundTrip(response, PromotionResponse.serializer())
        assertRoundTrip(redemption, RedemptionResponse.serializer())
        assertRoundTrip(promotionRequest, PromotionRequest.serializer())
        assertRoundTrip(CouponRequest("promotion-1", "SAVE10", 5, 1), CouponRequest.serializer())
        assertRoundTrip(CouponUpdateRequest("ACTIVE", 5, 1), CouponUpdateRequest.serializer())
    }

    @Test
    fun `optional promotion fields and empty collections preserve defaults`() {
        val value = PromotionRequest("Sale", PromotionType.FREE_SHIPPING, startAt = "2026-08-20T00:00:00Z")
        val decoded = json.decodeFromString(PromotionRequest.serializer(), json.encodeToString(PromotionRequest.serializer(), value))
        assertEquals(value, decoded)
        assertEquals(value, json.decodeFromString(PromotionRequest.serializer(), "{\"name\":\"Sale\",\"type\":\"FREE_SHIPPING\",\"startAt\":\"2026-08-20T00:00:00Z\"}"))
        assertEquals(emptyList(), decoded.productIds)
        assertEquals(0L, PromotionLine("p", "v", quantity = 0, unitPriceMinor = 10).lineTotalMinor)

        listOf(
            value.copy(status = PromotionStatus.ACTIVE),
            value.copy(endAt = "2026-08-21T00:00:00Z"),
            value.copy(currency = "USD"),
            value.copy(minOrderMinor = 1),
            value.copy(maxDiscountMinor = 500),
            value.copy(percentageBps = 1_000),
            value.copy(fixedAmountMinor = 100),
            value.copy(buyQuantity = 2),
            value.copy(getQuantity = 1),
            value.copy(productIds = listOf("product-1")),
            value.copy(categoryIds = listOf("category-1")),
            value.copy(sellerIds = listOf("seller-1")),
            value.copy(usageLimit = 10),
            value.copy(perUserLimit = 1),
            value.copy(stackPolicy = StackPolicy.BEST_DISCOUNT_ONLY),
            value.copy(priority = 1),
            value.copy(customerSegments = listOf("VIP")),
        ).forEach { variant ->
            assertEquals(
                variant,
                compactJson.decodeFromString(PromotionRequest.serializer(), compactJson.encodeToString(PromotionRequest.serializer(), variant)),
            )
        }
    }

    @Test
    fun `promotion wire models exercise nullable fields in both directions`() {
        val calculateWithoutOptionals = PromotionCalculateRequest(lines = emptyList())
        assertRoundTrip(calculateWithoutOptionals, PromotionCalculateRequest.serializer())

        val quoteWithReason = PromotionQuote("promotion-1", "SAVE", "INR", 10, false, emptyMap(), "not eligible")
        assertRoundTrip(quoteWithReason, PromotionQuote.serializer())

        val requestWithFixedAmount = PromotionRequest(
            name = "Fixed",
            type = PromotionType.FIXED_AMOUNT,
            startAt = "2026-08-20T00:00:00Z",
            endAt = "2026-08-21T00:00:00Z",
            maxDiscountMinor = 500,
            fixedAmountMinor = 250,
            productIds = listOf("product-1"),
            categoryIds = listOf("category-1"),
            sellerIds = listOf("seller-1"),
            usageLimit = 10,
            perUserLimit = 1,
            customerSegments = listOf("VIP")
        )
        assertRoundTrip(requestWithFixedAmount, PromotionRequest.serializer())
        assertEquals(CouponRequest("promotion-1", "SAVE10"), json.decodeFromString<CouponRequest>("{\"promotionId\":\"promotion-1\",\"code\":\"SAVE10\"}"))
        assertEquals(CouponUpdateRequest("ACTIVE"), json.decodeFromString<CouponUpdateRequest>("{\"status\":\"ACTIVE\"}"))
    }

    @Test
    fun `promotion payloads cover omitted and explicit null optional branches`() {
        val sparseLine = json.decodeFromString<PromotionLine>("{\"productId\":\"p1\",\"variantId\":\"v1\",\"quantity\":1,\"unitPriceMinor\":99}")
        assertEquals(null, sparseLine.categoryId)
        assertEquals(null, sparseLine.sellerId)
        val sparseCalculate = json.decodeFromString<PromotionCalculateRequest>("{\"lines\":[]}")
        assertEquals("INR", sparseCalculate.currency)
        assertEquals(null, sparseCalculate.couponCode)
        assertEquals(null, sparseCalculate.promotionId)
        assertEquals(0L, sparseCalculate.shippingMinor)
        assertEquals("DEFAULT", sparseCalculate.customerSegment)
        val nullQuote = json.decodeFromString<PromotionQuote>("{\"promotionId\":null,\"couponCode\":null,\"currency\":\"INR\",\"discountMinor\":0,\"freeShipping\":false,\"reason\":null}")
        assertEquals(emptyMap(), nullQuote.eligibleLineDiscounts)
        val nullResponse = json.decodeFromString<PromotionResponse>("{\"id\":\"p1\",\"name\":\"Sale\",\"type\":\"PERCENTAGE\",\"status\":\"DRAFT\",\"startAt\":\"2026-08-20T00:00:00Z\",\"endAt\":null,\"currency\":\"INR\",\"minOrderMinor\":0,\"maxDiscountMinor\":null,\"percentageBps\":null,\"fixedAmountMinor\":null,\"productIds\":[],\"categoryIds\":[],\"sellerIds\":[],\"usageLimit\":null,\"usageCount\":0,\"perUserLimit\":null,\"stackPolicy\":\"NO_STACK\",\"priority\":0,\"version\":1}")
        assertEquals(emptyList(), nullResponse.customerSegments)
        assertRoundTrip(nullResponse, PromotionResponse.serializer())
        val nullRedemption = json.decodeFromString<RedemptionResponse>("{\"id\":\"r1\",\"promotionId\":\"p1\",\"couponCode\":null,\"userId\":\"u1\",\"orderId\":null,\"discountMinor\":0,\"currency\":\"INR\",\"status\":\"RELEASED\",\"quote\":{\"promotionId\":null,\"couponCode\":null,\"currency\":\"INR\",\"discountMinor\":0,\"freeShipping\":false},\"createdAt\":\"2026-08-20T00:00:00Z\"}")
        assertEquals(null, nullRedemption.couponCode)
        assertEquals(null, nullRedemption.orderId)
    }

    @Test
    fun `promotion response serialization preserves every optional value`() {
        val complete = PromotionResponse(
            id = "p1", name = "Sale", type = PromotionType.PERCENTAGE, status = PromotionStatus.ACTIVE,
            startAt = "2026-08-20T00:00:00Z", endAt = "2026-08-31T23:59:59Z", currency = "INR",
            minOrderMinor = 100, maxDiscountMinor = 500, percentageBps = 1_000, fixedAmountMinor = 250,
            productIds = listOf("product-1"), categoryIds = listOf("category-1"), sellerIds = listOf("seller-1"),
            usageLimit = 10, usageCount = 2, perUserLimit = 1, stackPolicy = StackPolicy.BEST_DISCOUNT_ONLY,
            priority = 2, version = 3, customerSegments = listOf("VIP"),
        )
        assertEquals(complete, compactJson.decodeFromString(PromotionResponse.serializer(), compactJson.encodeToString(PromotionResponse.serializer(), complete)))
        val omittedSegments = compactJson.decodeFromString<PromotionResponse>(compactJson.encodeToString(complete.copy(customerSegments = emptyList())))
        assertEquals(emptyList(), omittedSegments.customerSegments)
    }

    @Test
    fun `compact promotion serialization omits defaults and still decodes business payloads`() {
        val line = PromotionLine("product-1", "variant-1", quantity = 1, unitPriceMinor = 100)
        val calculate = PromotionCalculateRequest(lines = listOf(line))
        val quote = PromotionQuote(null, null, "INR", 0, false)
        val request = PromotionRequest("Sale", PromotionType.PERCENTAGE, startAt = "2026-08-20T00:00:00Z")
        val coupon = CouponRequest("promotion-1", "SAVE10")
        val update = CouponUpdateRequest("ACTIVE")

        assertEquals(line, compactJson.decodeFromString(PromotionLine.serializer(), compactJson.encodeToString(PromotionLine.serializer(), line)))
        assertEquals(calculate, compactJson.decodeFromString(PromotionCalculateRequest.serializer(), compactJson.encodeToString(PromotionCalculateRequest.serializer(), calculate)))
        assertEquals(quote, compactJson.decodeFromString(PromotionQuote.serializer(), compactJson.encodeToString(PromotionQuote.serializer(), quote)))
        assertEquals(request, compactJson.decodeFromString(PromotionRequest.serializer(), compactJson.encodeToString(PromotionRequest.serializer(), request)))
        assertEquals(coupon, compactJson.decodeFromString(CouponRequest.serializer(), compactJson.encodeToString(CouponRequest.serializer(), coupon)))
        assertEquals(update, compactJson.decodeFromString(CouponUpdateRequest.serializer(), compactJson.encodeToString(CouponUpdateRequest.serializer(), update)))
        listOf(
            calculate.copy(currency = "USD"),
            calculate.copy(couponCode = "SAVE10"),
            calculate.copy(promotionId = "promotion-1"),
            calculate.copy(shippingMinor = 100),
            calculate.copy(customerSegment = "VIP"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(PromotionCalculateRequest.serializer(), compactJson.encodeToString(PromotionCalculateRequest.serializer(), value)))
        }
    }

    @Test
    fun `percentage discount enforces financial boundaries exactly`() {
        assertEquals(0, percentageDiscountMinor(1_000, 0))
        assertEquals(1_000, percentageDiscountMinor(1_000, 10_000))
        assertEquals(125, percentageDiscountMinor(1_000, 1_250))
        assertFailsWith<IllegalArgumentException> { percentageDiscountMinor(-1, 100) }
        assertFailsWith<IllegalArgumentException> { percentageDiscountMinor(1_000, -1) }
        assertFailsWith<IllegalArgumentException> { percentageDiscountMinor(1_000, 10_001) }
    }

    private fun <T> assertRoundTrip(value: T, serializer: KSerializer<T>) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }
}
