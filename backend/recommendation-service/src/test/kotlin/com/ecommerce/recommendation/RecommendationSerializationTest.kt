package com.ecommerce.recommendation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendationSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `behavior event preserves optional identity and product context`() {
        val event = BehaviorRequest("PRODUCT_VIEWED", "user-1", "session-1", "product-1", "running shoes")
        assertEquals(event, json.decodeFromString<BehaviorRequest>(json.encodeToString(event)))
        assertEquals(BehaviorRequest("SEARCH"), json.decodeFromString(json.encodeToString(BehaviorRequest("SEARCH"))))
    }

    @Test
    fun `all recommendation types and cache metadata round trip`() {
        RecommendationType.entries.forEach { type ->
            val response = RecommendationResponse(type, listOf("product-1", "product-2"), "rule-based", cached = true)
            assertEquals(response, json.decodeFromString<RecommendationResponse>(json.encodeToString(response)))
        }
    }

    @Test
    fun `compact recommendation serialization preserves cold-start event and cache defaults`() {
        val event = BehaviorRequest("PRODUCT_VIEWED")
        val response = RecommendationResponse(RecommendationType.TRENDING, emptyList(), "popular-fallback")
        assertEquals(event, compactJson.decodeFromString(BehaviorRequest.serializer(), compactJson.encodeToString(BehaviorRequest.serializer(), event)))
        assertEquals(response, compactJson.decodeFromString(RecommendationResponse.serializer(), compactJson.encodeToString(RecommendationResponse.serializer(), response)))
        listOf(
            event,
            event.copy(userId = "user-1"),
            event.copy(sessionId = "session-1"),
            event.copy(productId = "product-1"),
            event.copy(query = "boots"),
            event.copy(userId = "user-1", sessionId = "session-1", productId = "product-1", query = "boots"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(BehaviorRequest.serializer(), compactJson.encodeToString(BehaviorRequest.serializer(), value)))
        }
    }
}
