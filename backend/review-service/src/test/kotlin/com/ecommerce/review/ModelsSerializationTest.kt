package com.ecommerce.review

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `review lifecycle models preserve rating and moderation data`() {
        val request = ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 5, "Great", "Exactly as described")
        val response = ReviewResponse("rev-1", "user-1", ReviewTarget.PRODUCT, "product-1", "order-1", 5, "Great", request.body, true, ReviewStatus.PUBLISHED, 3, "2026-08-20T00:00:00Z")
        val page = ReviewPage(listOf(response), "20")
        val moderation = ModerationRequest(ReviewStatus.HIDDEN, "needs review")
        val report = ReportRequest("abuse")
        val aggregate = RatingAggregate(ReviewTarget.PRODUCT, "product-1", 1, 5.0)

        assertEquals(request, json.decodeFromString<ReviewRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<ReviewResponse>(json.encodeToString(response)))
        assertEquals(page, json.decodeFromString<ReviewPage>(json.encodeToString(page)))
        assertEquals(moderation, json.decodeFromString<ModerationRequest>(json.encodeToString(moderation)))
        assertEquals(report, json.decodeFromString<ReportRequest>(json.encodeToString(report)))
        assertEquals(aggregate, json.decodeFromString<RatingAggregate>(json.encodeToString(aggregate)))
    }

    @Test
    fun `compact review serialization preserves empty pages and optional moderation text`() {
        val request = ReviewRequest(ReviewTarget.SELLER, "seller-1", "order-1", 1, body = "Needs improvement")
        val page = ReviewPage(emptyList(), null)
        val moderation = ModerationRequest(ReviewStatus.SUBMITTED)
        val aggregate = RatingAggregate(ReviewTarget.SELLER, "seller-1", 0, 0.0)
        assertEquals(request, compactJson.decodeFromString(ReviewRequest.serializer(), compactJson.encodeToString(ReviewRequest.serializer(), request)))
        assertEquals(page, compactJson.decodeFromString(ReviewPage.serializer(), compactJson.encodeToString(ReviewPage.serializer(), page)))
        assertEquals(moderation, compactJson.decodeFromString(ModerationRequest.serializer(), compactJson.encodeToString(ModerationRequest.serializer(), moderation)))
        assertEquals(aggregate, compactJson.decodeFromString(RatingAggregate.serializer(), compactJson.encodeToString(RatingAggregate.serializer(), aggregate)))
    }
}
