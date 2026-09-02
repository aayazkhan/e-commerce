package com.ecommerce.notification

import kotlinx.serialization.Serializable

@Serializable enum class NotificationChannel { PUSH, EMAIL, SMS, IN_APP }
@Serializable enum class NotificationStatus { QUEUED, PROCESSING, SENT, DELIVERED, FAILED, SUPPRESSED, DLQ }
@Serializable data class PreferenceRequest(val emailEnabled: Boolean = true, val smsEnabled: Boolean = true, val pushEnabled: Boolean = true, val inAppEnabled: Boolean = true, val quietStart: String? = null, val quietEnd: String? = null, val timezone: String = "Asia/Kolkata", val locale: String = "en-IN")
@Serializable data class DeviceRequest(val platform: String, val token: String)
@Serializable data class NotificationResponse(val id: String, val channel: NotificationChannel, val status: NotificationStatus, val createdAt: String)
@Serializable data class InAppResponse(val id: String, val title: String, val body: String, val read: Boolean, val createdAt: String)
@Serializable data class WebhookRequest(val providerMessageId: String, val status: String, val signature: String? = null)
@Serializable data class TemplateRequest(val templateKey: String, val locale: String, val subject: String, val body: String)

object NotificationRetryPolicy {
    const val maxAttempts = 5
    fun backoffSeconds(attempt: Int): Int = (5L shl (attempt.coerceAtLeast(1) - 1)).coerceAtMost(3600).toInt()
    fun isTerminal(attempt: Int): Boolean = attempt >= maxAttempts
}
