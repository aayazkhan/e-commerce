package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.config.KafkaConfig
import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutboxPublisherTest {
    private val event = OutboxRecord("evt-1", "User", "user-1", "UserRegistered", 1, Instant.parse("2026-08-21T00:00:00Z"), "corr-1", "{\"userId\":\"user-1\"}")
    private val config = KafkaConfig("unused", "identity.events.v1", "tenant-1")

    @Test
    fun `empty identity outbox is a no-op`() {
        val store = FakeStore(emptyList())
        val producer = FakeProducer()
        OutboxPublisher(store, config, producer).publishOnce()
        assertEquals(100, store.limit)
        assertEquals(emptyList(), producer.messages)
        assertEquals(emptyList(), store.marked)
    }

    @Test
    fun `identity outbox publishes envelope and marks event`() {
        val store = FakeStore(listOf(event))
        val producer = FakeProducer()
        OutboxPublisher(store, config, producer).publishOnce()
        val message = producer.messages.single()
        assertEquals("identity.events.v1", message.first)
        assertEquals("user-1", message.second)
        val envelope = Json.decodeFromString<EventEnvelope>(message.third)
        assertEquals("evt-1", envelope.eventId)
        assertEquals("identity-service", envelope.producer)
        assertEquals("tenant-1", envelope.tenantId)
        assertEquals("{\"userId\":\"user-1\"}", envelope.payloadJson)
        assertEquals(listOf("evt-1"), store.marked)
    }

    @Test
    fun `identity producer failure leaves event unpublished`() {
        val store = FakeStore(listOf(event))
        val producer = FakeProducer().also { it.failure = IllegalStateException("broker unavailable") }
        assertFailsWith<IllegalStateException> { OutboxPublisher(store, config, producer).publishOnce() }
        assertEquals(emptyList(), store.marked)
    }

    @Test
    fun `identity publisher close flushes and closes`() {
        val producer = FakeProducer()
        OutboxPublisher(FakeStore(emptyList()), config, producer).close()
        assertEquals(1, producer.flushes)
        assertEquals(1, producer.closes)
    }

    @Test
    fun `identity publisher start publishes the first batch before shutdown`() {
        val store = FakeStore(listOf(event))
        val producer = FakeProducer()
        val publisher = OutboxPublisher(store, config, producer)

        publisher.start(CoroutineScope(Dispatchers.Default))
        assertTrue(store.markedLatch.await(2, TimeUnit.SECONDS))
        publisher.close()

        assertEquals(listOf("evt-1"), store.marked)
        assertEquals(1, producer.messages.size)
        assertEquals(1, producer.flushes)
        assertEquals(1, producer.closes)
    }

    private class FakeStore(private val events: List<OutboxRecord>) : IdentityOutboxStore {
        var limit = 0
        val marked = mutableListOf<String>()
        val markedLatch = CountDownLatch(1)
        override fun unpublishedOutbox(limit: Int): List<OutboxRecord> { this.limit = limit; return events }
        override fun markOutboxPublished(ids: Collection<String>, publishedAt: Instant) { marked += ids; markedLatch.countDown() }
    }

    private class FakeProducer : IdentityOutboxProducer {
        val messages = mutableListOf<Triple<String, String, String>>()
        var failure: Throwable? = null
        var flushes = 0
        var closes = 0
        override fun send(topic: String, key: String, value: String) { failure?.let { throw it }; messages += Triple(topic, key, value) }
        override fun flush() { flushes++ }
        override fun close() { closes++ }
    }
}
