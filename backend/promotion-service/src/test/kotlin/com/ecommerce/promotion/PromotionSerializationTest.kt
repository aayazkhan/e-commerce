package com.ecommerce.promotion

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PromotionSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `promotion request response quote and redemption round trip`() {
        PromotionType.entries.forEach { type ->
            val request = PromotionRequest("Summer sale", type, PromotionStatus.ACTIVE, "2026-08-20T00:00:00Z", "2026-08-31T23:59:59Z", "INR", 1000, 5000, 1000, null, 2, 1, listOf("product-1"), listOf("category-1"), listOf("seller-1"), 100, 1, StackPolicy.BEST_DISCOUNT_ONLY, 10, listOf("DEFAULT"))
            assertEquals(request, json.decodeFromString<PromotionRequest>(json.encodeToString(request)))
        }
        val line = PromotionLine("product-1", "variant-1", "category-1", "seller-1", 2, 1000)
        val quote = PromotionQuote("promotion-1", "SUMMER", "INR", 200, true, mapOf("variant-1" to 200), null)
        val response = RedemptionResponse("redemption-1", "promotion-1", "SUMMER", "user-1", "order-1", 200, "INR", RedemptionStatus.COMMITTED, quote, "2026-08-20T10:00:00Z")
        assertEquals(line, json.decodeFromString<PromotionLine>(json.encodeToString(line)))
        assertEquals(2000, line.lineTotalMinor)
        assertEquals(quote, json.decodeFromString<PromotionQuote>(json.encodeToString(quote)))
        assertEquals(response, json.decodeFromString<RedemptionResponse>(json.encodeToString(response)))
        val calculate = PromotionCalculateRequest(lines = listOf(line))
        listOf(
            RedemptionRequest(calculate),
            RedemptionRequest(calculate, "order-1"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(RedemptionRequest.serializer(), compactJson.encodeToString(RedemptionRequest.serializer(), value)))
        }
        listOf(
            InternalRedemptionRequest("user-1", calculate),
            InternalRedemptionRequest("user-1", calculate, "order-1"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(InternalRedemptionRequest.serializer(), compactJson.encodeToString(InternalRedemptionRequest.serializer(), value)))
        }
    }

    @Test
    fun `promotion request defaults decode from sparse and compact payloads`() {
        val minimal = PromotionRequest(
            name = "Minimal sale",
            type = PromotionType.FIXED_AMOUNT,
            startAt = "2026-08-20T00:00:00Z",
        )

        assertEquals(minimal, json.decodeFromString<PromotionRequest>("""
            {"name":"Minimal sale","type":"FIXED_AMOUNT","startAt":"2026-08-20T00:00:00Z"}
        """))
        assertEquals(minimal, compactJson.decodeFromString(compactJson.encodeToString(minimal)))
        assertEquals(minimal, json.decodeFromString(compactJson.encodeToString(minimal)))
    }

    @Test
    fun `promotion request serialization preserves fixed amount and nullable limit combinations`() {
        val fixed = PromotionRequest(
            name = "Fixed sale",
            type = PromotionType.FIXED_AMOUNT,
            status = PromotionStatus.PAUSED,
            startAt = "2026-08-20T00:00:00Z",
            endAt = null,
            currency = "USD",
            minOrderMinor = 250,
            maxDiscountMinor = null,
            percentageBps = null,
            fixedAmountMinor = 125,
            buyQuantity = null,
            getQuantity = null,
            productIds = emptyList(),
            categoryIds = emptyList(),
            sellerIds = emptyList(),
            usageLimit = null,
            perUserLimit = null,
            stackPolicy = StackPolicy.PRIORITY_BASED,
            priority = 3,
            customerSegments = emptyList(),
        )

        assertEquals(fixed, json.decodeFromString<PromotionRequest>(json.encodeToString(fixed)))
        assertEquals(fixed, compactJson.decodeFromString(compactJson.encodeToString(fixed)))
    }
}
