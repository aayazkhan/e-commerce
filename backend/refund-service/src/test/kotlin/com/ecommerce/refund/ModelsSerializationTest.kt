package com.ecommerce.refund

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `refund request, decision, and response preserve financial fields`() {
        val item = RefundItem("variant-1", 2, 1_000)
        val request = RefundRequest("order-1", "pay-1", 1_000, "INR", RefundType.ITEM_LEVEL, "damaged", listOf(item))
        val decision = RefundDecision(true, "approved")
        val response = RefundResponse("rfd-1", "user-1", "order-1", "pay-1", 1_000, "INR", RefundType.ITEM_LEVEL, RefundStatus.COMPLETED, "damaged", listOf(item), "provider-rfd-1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:01Z")
        val provider = PaymentRefundResult("pay-1", 1_000, "COMPLETED", "provider-rfd-1", "2026-08-20T00:00:01Z")

        assertEquals(request, json.decodeFromString<RefundRequest>(json.encodeToString(request)))
        assertEquals(decision, json.decodeFromString<RefundDecision>(json.encodeToString(decision)))
        assertEquals(response, json.decodeFromString<RefundResponse>(json.encodeToString(response)))
        assertEquals(provider, json.decodeFromString<PaymentRefundResult>(json.encodeToString(provider)))
    }

    @Test
    fun `compact refund serialization preserves empty items and optional decision values`() {
        val request = RefundRequest("order-1", "pay-1", 0, "INR", RefundType.FULL, "customer request")
        val decision = RefundDecision(false)
        val response = RefundResponse("rfd-1", "user-1", "order-1", "pay-1", 0, "INR", RefundType.FULL, RefundStatus.REQUESTED, "customer request", emptyList(), null, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val provider = PaymentRefundResult("pay-1", 0, "PENDING", null, "2026-08-20T00:00:00Z")
        assertEquals(request, compactJson.decodeFromString(RefundRequest.serializer(), compactJson.encodeToString(RefundRequest.serializer(), request)))
        assertEquals(decision, compactJson.decodeFromString(RefundDecision.serializer(), compactJson.encodeToString(RefundDecision.serializer(), decision)))
        assertEquals(response, compactJson.decodeFromString(RefundResponse.serializer(), compactJson.encodeToString(RefundResponse.serializer(), response)))
        assertEquals(provider, compactJson.decodeFromString(PaymentRefundResult.serializer(), compactJson.encodeToString(PaymentRefundResult.serializer(), provider)))
    }
}
