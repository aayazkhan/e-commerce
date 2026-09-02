package com.ecommerce.notification

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `notification models preserve all delivery channels and statuses`() {
        NotificationChannel.entries.forEach { channel ->
            NotificationStatus.entries.forEach { status ->
                val response = NotificationResponse("notification-$channel", channel, status, "2026-08-20T10:00:00Z")
                assertEquals(response, json.decodeFromString<NotificationResponse>(json.encodeToString(response)))
            }
        }
    }

    @Test
    fun `preferences device in-app webhook and template models round trip`() {
        val preference = PreferenceRequest(false, true, false, true, "22:00", "07:00", "Asia/Kolkata", "hi-IN")
        val device = DeviceRequest("ANDROID", "device-token")
        val inApp = InAppResponse("in-app-1", "Order shipped", "Track your package", false, "2026-08-20T10:00:00Z")
        val webhook = WebhookRequest("provider-message", "delivered", "signature")
        val template = TemplateRequest("order.shipped", "en-IN", "Your order shipped", "Order {{orderId}} is on its way")
        assertEquals(preference, json.decodeFromString<PreferenceRequest>(json.encodeToString(preference)))
        assertEquals(device, json.decodeFromString<DeviceRequest>(json.encodeToString(device)))
        assertEquals(inApp, json.decodeFromString<InAppResponse>(json.encodeToString(inApp)))
        assertEquals(webhook, json.decodeFromString<WebhookRequest>(json.encodeToString(webhook)))
        assertEquals(template, json.decodeFromString<TemplateRequest>(json.encodeToString(template)))
        assertTrue(NotificationRetryPolicy.backoffSeconds(100) <= 3600)
    }

    @Test
    fun `notification optional fields cover quiet-hour and webhook defaults`() {
        val defaults = PreferenceRequest()
        val decodedDefaults = json.decodeFromString<PreferenceRequest>(json.encodeToString(defaults))
        assertEquals(defaults, decodedDefaults)
        assertEquals(null, decodedDefaults.quietStart)
        assertEquals(null, decodedDefaults.quietEnd)
        assertEquals(defaults, json.decodeFromString<PreferenceRequest>("{}"))

        val disabled = PreferenceRequest(false, false, false, false, null, null, "UTC", "en-US")
        assertEquals(disabled, json.decodeFromString<PreferenceRequest>(json.encodeToString(disabled)))
        val webhookWithoutSignature = WebhookRequest("provider-message", "failed")
        assertEquals(webhookWithoutSignature, json.decodeFromString<WebhookRequest>(json.encodeToString(webhookWithoutSignature)))
        assertEquals(webhookWithoutSignature, json.decodeFromString<WebhookRequest>("{\"providerMessageId\":\"provider-message\",\"status\":\"failed\"}"))
    }

    @Test
    fun `compact notification serialization preserves preference defaults and optional webhook signature`() {
        val preference = PreferenceRequest()
        val notification = NotificationResponse("notification-1", NotificationChannel.IN_APP, NotificationStatus.QUEUED, "2026-08-20T00:00:00Z")
        val inApp = InAppResponse("in-app-1", "Title", "Body", false, "2026-08-20T00:00:00Z")
        val webhook = WebhookRequest("provider-message", "delivered")
        assertEquals(preference, compactJson.decodeFromString(PreferenceRequest.serializer(), compactJson.encodeToString(PreferenceRequest.serializer(), preference)))
        assertEquals(notification, compactJson.decodeFromString(NotificationResponse.serializer(), compactJson.encodeToString(NotificationResponse.serializer(), notification)))
        assertEquals(inApp, compactJson.decodeFromString(InAppResponse.serializer(), compactJson.encodeToString(InAppResponse.serializer(), inApp)))
        assertEquals(webhook, compactJson.decodeFromString(WebhookRequest.serializer(), compactJson.encodeToString(WebhookRequest.serializer(), webhook)))

        listOf(
            preference.copy(emailEnabled = false),
            preference.copy(smsEnabled = false),
            preference.copy(pushEnabled = false),
            preference.copy(inAppEnabled = false),
            preference.copy(quietStart = "22:00"),
            preference.copy(quietEnd = "07:00"),
            preference.copy(timezone = "UTC"),
            preference.copy(locale = "hi-IN"),
        ).forEach { variant ->
            assertEquals(
                variant,
                compactJson.decodeFromString(PreferenceRequest.serializer(), compactJson.encodeToString(PreferenceRequest.serializer(), variant)),
            )
        }
    }
}
