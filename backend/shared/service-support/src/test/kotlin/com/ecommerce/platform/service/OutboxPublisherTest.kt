package com.ecommerce.platform.service

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutboxPublisherTest {
    private val record = ServiceOutboxRecord("evt-1", "Order", "order-1", "OrderCreated", 1, Instant.parse("2026-08-21T00:00:00Z"), "corr-1", "{\"orderId\":\"order-1\"}")

    @Test
    fun `empty outbox does not publish or mark anything`() {
        val store = FakeStore(emptyList())
        val producer = FakeProducer()
        KafkaOutboxPublisher(store, ServiceKafkaConfig("unused", "events", "tenant-1"), "test-service", producer).publishOnce()
        assertEquals(100, store.requestedLimit)
        assertEquals(0, producer.messages.size)
        assertEquals(emptyList(), store.marked)
    }

    @Test
    fun `outbox publishes envelopes and marks the exact records`() {
        val store = FakeStore(listOf(record))
        val producer = FakeProducer()
        KafkaOutboxPublisher(store, ServiceKafkaConfig("unused", "events", "tenant-1"), "test-service", producer).publishOnce()
        assertEquals(listOf("events" to "order-1"), producer.messages.map { it.first to it.second })
        val envelope = Json.decodeFromString<EventEnvelope>(producer.messages.single().third)
        assertEquals("evt-1", envelope.eventId)
        assertEquals("OrderCreated", envelope.eventType)
        assertEquals("test-service", envelope.producer)
        assertEquals("tenant-1", envelope.tenantId)
        assertEquals("{\"orderId\":\"order-1\"}", envelope.payloadJson)
        assertEquals(listOf("evt-1"), store.marked)
    }

    @Test
    fun `producer failure prevents marking and propagates to caller`() {
        val store = FakeStore(listOf(record))
        val producer = FakeProducer().also { it.failure = IllegalStateException("kafka unavailable") }
        val publisher = KafkaOutboxPublisher(store, ServiceKafkaConfig("unused", "events", "tenant-1"), "test-service", producer)
        assertFailsWith<IllegalStateException> { publisher.publishOnce() }
        assertEquals(emptyList(), store.marked)
    }

    @Test
    fun `close flushes and closes producer even without a started job`() {
        val producer = FakeProducer()
        KafkaOutboxPublisher(FakeStore(emptyList()), ServiceKafkaConfig("unused", "events", "tenant-1"), "test-service", producer).close()
        assertEquals(1, producer.flushes)
        assertEquals(1, producer.closes)
    }

    @Test
    fun `start publishes once and stops cleanly when its pause is cancelled`() {
        val store = FakeStore(listOf(record))
        val producer = FakeProducer()
        val publisher = KafkaOutboxPublisher(
            store,
            ServiceKafkaConfig("unused", "events", "tenant-1"),
            "test-service",
            producer,
            pause = { throw CancellationException("test stop") },
            dispatcher = Dispatchers.Unconfined,
        )

        publisher.start(CoroutineScope(Dispatchers.Unconfined))
        publisher.close()

        assertEquals(listOf("evt-1"), store.marked)
        assertEquals(1, producer.messages.size)
        assertEquals(1, producer.flushes)
        assertEquals(1, producer.closes)
    }

    @Test
    fun `start swallows a publish failure and still stops on cancellation`() {
        val store = FakeStore(listOf(record))
        val producer = FakeProducer().also { it.failure = IllegalStateException("kafka unavailable") }
        val publisher = KafkaOutboxPublisher(
            store,
            ServiceKafkaConfig("unused", "events", "tenant-1"),
            "test-service",
            producer,
            pause = { throw CancellationException("test stop") },
            dispatcher = Dispatchers.Unconfined,
        )

        publisher.start(CoroutineScope(Dispatchers.Unconfined))
        publisher.close()

        assertEquals(emptyList(), store.marked)
        assertEquals(emptyList(), producer.messages)
    }

    private class FakeStore(private val records: List<ServiceOutboxRecord>) : ServiceOutboxStore {
        var requestedLimit = 0
        val marked = mutableListOf<String>()
        override fun unpublished(limit: Int): List<ServiceOutboxRecord> { requestedLimit = limit; return records }
        override fun markPublished(ids: List<String>, publishedAt: Instant) { marked += ids }
    }

    private class FakeProducer : OutboxProducer {
        val messages = mutableListOf<Triple<String, String, String>>()
        var failure: Throwable? = null
        var flushes = 0
        var closes = 0
        override fun send(topic: String, key: String, value: String) { failure?.let { throw it }; messages += Triple(topic, key, value) }
        override fun flush() { flushes++ }
        override fun close() { closes++ }
    }
}
