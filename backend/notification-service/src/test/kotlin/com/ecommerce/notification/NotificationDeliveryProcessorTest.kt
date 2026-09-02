package com.ecommerce.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationDeliveryProcessorTest {
    private val delivery = Delivery(
        id = "delivery-1",
        eventId = "event-1",
        userId = "user-1",
        channel = NotificationChannel.PUSH,
        templateKey = "order.created",
        locale = "en-IN",
        subject = "Order created",
        body = "Your order was created.",
        attempts = 0,
        provider = null,
        platform = "ANDROID",
    )

    @Test
    fun `in-app delivery marks sent only after the in-app write succeeds`() {
        val sent = mutableListOf<List<String?>>()
        val failures = mutableListOf<Throwable>()

        processNotificationDelivery(
            delivery.copy(channel = NotificationChannel.IN_APP),
            emptyMap(),
            inApp = { item -> assertEquals("delivery-1", item.id) },
            sent = { id, provider, messageId -> sent += listOf(id, provider, messageId) },
            failed = { _, error -> failures += error },
        )

        assertEquals(listOf(listOf("delivery-1", "in-app", null)), sent)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `in-app write failure is routed to failure handling`() {
        val failures = mutableListOf<Pair<String, String?>>()

        processNotificationDelivery(
            delivery.copy(channel = NotificationChannel.IN_APP),
            emptyMap(),
            inApp = { error("database unavailable") },
            sent = { _, _, _ -> error("sent must not be called") },
            failed = { item, error -> failures += item.id to error.message },
        )

        assertEquals(1, failures.size)
        assertEquals("delivery-1", failures.single().first)
        assertEquals("database unavailable", failures.single().second)
    }

    @Test
    fun `email and sms use their configured providers and preserve provider message ids`() {
        val sent = mutableListOf<Pair<String, String?>>()
        val providers = mapOf(
            "email" to FakeProvider("email", "email-message"),
            "sms" to FakeProvider("sms", "sms-message"),
        )

        processNotificationDelivery(delivery.copy(channel = NotificationChannel.EMAIL), providers, { _: Delivery -> }, { _, provider, message -> sent += provider to message }, { _, error -> error("unexpected ${error.message}") })
        processNotificationDelivery(delivery.copy(channel = NotificationChannel.SMS), providers, { _: Delivery -> }, { _, provider, message -> sent += provider to message }, { _, error -> error("unexpected ${error.message}") })

        assertEquals(2, sent.size)
        assertEquals("email", sent[0].first)
        assertEquals("email-message", sent[0].second)
        assertEquals("sms", sent[1].first)
        assertEquals("sms-message", sent[1].second)
    }

    @Test
    fun `push selects apns for ios and fcm for other platforms`() {
        val sent = mutableListOf<String>()
        val providers = mapOf(
            "apns" to FakeProvider("apns", "apns-message"),
            "fcm" to FakeProvider("fcm", "fcm-message"),
        )

        processNotificationDelivery(delivery.copy(platform = "IOS"), providers, { _: Delivery -> }, { _, provider, _ -> sent += provider }, { _, error -> error("unexpected ${error.message}") })
        processNotificationDelivery(delivery.copy(platform = "android"), providers, { _: Delivery -> }, { _, provider, _ -> sent += provider }, { _, error -> error("unexpected ${error.message}") })

        assertEquals(listOf("apns", "fcm"), sent)
    }

    @Test
    fun `missing or failing provider is routed to failure handling`() {
        val failures = mutableListOf<String?>()
        val missing = delivery.copy(channel = NotificationChannel.EMAIL)
        processNotificationDelivery(missing, emptyMap(), { _: Delivery -> }, { _, _, _ -> error("sent must not be called") }, { _, error -> failures += error.message })

        val failing = mapOf("email" to FakeProvider("email", failure = IllegalStateException("provider timeout")))
        processNotificationDelivery(missing, failing, { _: Delivery -> }, { _, _, _ -> error("sent must not be called") }, { _, error -> failures += error.message })

        assertEquals(2, failures.size)
        assertEquals("Provider is not configured for email", failures[0])
        assertEquals("provider timeout", failures[1])
    }

    private class FakeProvider(
        override val name: String,
        private val messageId: String? = null,
        private val failure: Throwable? = null,
    ) : NotificationProvider {
        override fun send(delivery: Delivery): String? {
            failure?.let { throw it }
            return messageId
        }
    }
}
