package com.ecommerce.pricing

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PricingSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `price quote lines and event payload round trip`() {
        val price = Price("price-1", "product-1", "variant-1", "seller-1", "INR", "IN", "DEFAULT", 2500, 2200, 1800, "2026-08-20T10:00:00Z", "2026-12-31T23:59:59Z", 3, "admin-1", "2026-08-20T10:00:00Z", "2026-08-20T10:01:00Z")
        val line = QuoteLine("product-1", "variant-1", 2, price.unitMinor, 4400, 792)
        val quote = PriceQuote(listOf(line), 4400, 100, 792, 149, 5241, "INR", "v3")
        val event = PriceEventPayload("price-1", "product-1", "variant-1", "INR", 3, 2200, 1800)
        assertEquals(price, json.decodeFromString<Price>(json.encodeToString(price)))
        assertEquals(quote, json.decodeFromString<PriceQuote>(json.encodeToString(quote)))
        assertEquals(event, json.decodeFromString<PriceEventPayload>(json.encodeToString(event)))
        assertEquals(2500, price.copy(saleMinor = null).unitMinor)
    }

    @Test
    fun `compact pricing serialization preserves base price fallback and nullable variants`() {
        val price = Price("price-1", "product-1", null, null, "INR", "IN", "DEFAULT", 1_000, null, 0, "2026-08-20T00:00:00Z", null, 1, "system", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val line = QuoteLine("product-1", null, 1, 1_000, 1_000, 0)
        val quote = PriceQuote(listOf(line), 1_000, 0, 0, 0, 1_000, "INR", "v1")
        val event = PriceEventPayload("price-1", "product-1", null, "INR", 1)
        assertEquals(price, compactJson.decodeFromString(Price.serializer(), compactJson.encodeToString(Price.serializer(), price)))
        assertEquals(quote, compactJson.decodeFromString(PriceQuote.serializer(), compactJson.encodeToString(PriceQuote.serializer(), quote)))
        assertEquals(event, compactJson.decodeFromString(PriceEventPayload.serializer(), compactJson.encodeToString(PriceEventPayload.serializer(), event)))
    }

    @Test
    fun `price request serialization covers nullable and default field combinations`() {
        val sparse = PriceRequest("product-1", baseMinor = 1_000, effectiveFrom = "2026-08-20T00:00:00Z")
        val complete = PriceRequest(
            productId = "product-1",
            variantId = "variant-1",
            sellerId = "seller-1",
            currency = "USD",
            region = "US",
            customerSegment = "VIP",
            baseMinor = 2_000,
            saleMinor = 1_800,
            taxRateBps = 750,
            effectiveFrom = "2026-08-20T00:00:00Z",
            effectiveTo = "2026-12-31T23:59:59Z",
        )

        assertEquals(sparse, json.decodeFromString<PriceRequest>(json.encodeToString(sparse)))
        assertEquals(complete, json.decodeFromString<PriceRequest>(json.encodeToString(complete)))
        assertEquals(sparse, compactJson.decodeFromString<PriceRequest>(compactJson.encodeToString(sparse)))
        assertEquals(complete, compactJson.decodeFromString<PriceRequest>(compactJson.encodeToString(complete)))
    }

    @Test
    fun `quote request serialization covers default context and nullable variants`() {
        val sparse = QuoteRequest(listOf(QuoteItemRequest("product-1", quantity = 1)))
        val complete = QuoteRequest(
            listOf(QuoteItemRequest("product-1", "variant-1", 2)),
            currency = "USD",
            country = "US",
            customerSegment = "VIP",
        )

        assertEquals(sparse, json.decodeFromString<QuoteRequest>(json.encodeToString(sparse)))
        assertEquals(complete, json.decodeFromString<QuoteRequest>(json.encodeToString(complete)))
        assertEquals(sparse, compactJson.decodeFromString<QuoteRequest>("{\"items\":[{\"productId\":\"product-1\",\"quantity\":1}] }"))

        listOf(
            sparse.copy(currency = "USD"),
            sparse.copy(country = "US"),
            sparse.copy(customerSegment = "VIP"),
            sparse.copy(currency = "USD", country = "US"),
            sparse.copy(currency = "USD", customerSegment = "VIP"),
            sparse.copy(country = "US", customerSegment = "VIP"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString<QuoteRequest>(compactJson.encodeToString(value)))
        }
    }
}
