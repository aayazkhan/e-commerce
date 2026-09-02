package com.ecommerce.analytics

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `metric and replay models preserve nullable windows and idempotency`() {
        val summary = MetricSummary("2026-08-20", null, 10, 4, 2, 3, 2, 1, 2, 1000, .5, .67)
        val request = ReplayRequest(null, "2026-08-21T00:00:00Z")
        val response = ReplayResponse("run-1", 10, true)

        assertEquals(summary, json.decodeFromString<MetricSummary>(json.encodeToString(summary)))
        assertEquals(request, json.decodeFromString<ReplayRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<ReplayResponse>(json.encodeToString(response)))
    }

    @Test
    fun `compact analytics serialization preserves empty replay windows and idempotency default`() {
        val summary = MetricSummary(events = 0, views = 0, searches = 0, carts = 0, orders = 0, paidOrders = 0, checkouts = 0, revenueMinor = 0, conversionRate = 0.0, cartAbandonmentRate = 0.0)
        val request = ReplayRequest()
        val response = ReplayResponse("run-1", 0)
        assertEquals(summary, compactJson.decodeFromString(MetricSummary.serializer(), compactJson.encodeToString(MetricSummary.serializer(), summary)))
        assertEquals(request, compactJson.decodeFromString(ReplayRequest.serializer(), compactJson.encodeToString(ReplayRequest.serializer(), request)))
        assertEquals(response, compactJson.decodeFromString(ReplayResponse.serializer(), compactJson.encodeToString(ReplayResponse.serializer(), response)))

        val completeSummary = MetricSummary("from", "to", 10, 8, 7, 6, 5, 4, 3, 2_000, .75, .25)
        val completeReplay = ReplayRequest("from", "to")
        val completeResponse = ReplayResponse("run-2", 10, false)
        assertEquals(completeSummary, compactJson.decodeFromString(MetricSummary.serializer(), compactJson.encodeToString(MetricSummary.serializer(), completeSummary)))
        assertEquals(completeReplay, compactJson.decodeFromString(ReplayRequest.serializer(), compactJson.encodeToString(ReplayRequest.serializer(), completeReplay)))
        assertEquals(completeResponse, compactJson.decodeFromString(ReplayResponse.serializer(), compactJson.encodeToString(ReplayResponse.serializer(), completeResponse)))
    }
}
