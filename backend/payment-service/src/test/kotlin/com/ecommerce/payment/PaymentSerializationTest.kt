package com.ecommerce.payment

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `payment request response webhook and refund models round trip`() {
        PaymentProviderName.entries.forEach { provider ->
            val request = PaymentCreateRequest("order-1", 5250, "INR", provider, "token", "https://shop.test/return")
            assertEquals(request, json.decodeFromString<PaymentCreateRequest>(json.encodeToString(request)))
        }
        PaymentStatus.entries.forEach { status ->
            val response = PaymentResponse("payment-1", "order-1", "user-1", PaymentProviderName.HTTP, "provider-1", status, 5250, "INR", 2, "secret", "2026-08-20T10:00:00Z", "2026-08-20T10:01:00Z")
            assertEquals(response, json.decodeFromString<PaymentResponse>(json.encodeToString(response)))
        }
        val webhook = PaymentWebhookRequest("event-1", "provider-1", PaymentStatus.CAPTURED, 5250, "INR", mapOf("kind" to "capture"))
        val request = RefundPaymentRequest(1000, "INR", "refund-key")
        val response = RefundPaymentResponse("payment-1", 1000, PaymentStatus.REFUND_PENDING, "refund-1", "2026-08-20T10:00:00Z")
        assertEquals(webhook, json.decodeFromString<PaymentWebhookRequest>(json.encodeToString(webhook)))
        assertEquals(request, json.decodeFromString<RefundPaymentRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<RefundPaymentResponse>(json.encodeToString(response)))
    }

    @Test
    fun `compact payment serialization preserves nullable provider values and empty payloads`() {
        val request = PaymentCreateRequest("order-1", 1_000, "INR", PaymentProviderName.COD, "token")
        val response = PaymentResponse("payment-1", "order-1", "user-1", PaymentProviderName.COD, null, PaymentStatus.CREATED, 1_000, "INR", 1, null, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val webhook = PaymentWebhookRequest("event-1", "provider-1", PaymentStatus.FAILED, 1_000, "INR")
        val refund = RefundPaymentResponse("payment-1", 1_000, PaymentStatus.REFUND_PENDING, null, "2026-08-20T00:00:00Z")
        assertEquals(request, compactJson.decodeFromString(PaymentCreateRequest.serializer(), compactJson.encodeToString(PaymentCreateRequest.serializer(), request)))
        assertEquals(response, compactJson.decodeFromString(PaymentResponse.serializer(), compactJson.encodeToString(PaymentResponse.serializer(), response)))
        assertEquals(webhook, compactJson.decodeFromString(PaymentWebhookRequest.serializer(), compactJson.encodeToString(PaymentWebhookRequest.serializer(), webhook)))
        assertEquals(refund, compactJson.decodeFromString(RefundPaymentResponse.serializer(), compactJson.encodeToString(RefundPaymentResponse.serializer(), refund)))
    }
}
