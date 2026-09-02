package com.ecommerce.search

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SearchConsumerTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val config = SearchConsumerConfig("kafka:9092", listOf("catalog-events"), "search-service")

    @Test
    fun `consumer deduplicates events and commits only non-empty polls`() {
        val repository = FakeInboxStore(processedIds = mutableSetOf("duplicate"))
        val writer = FakeSearchWriter()
        val kafka = FakeKafkaConsumer(listOf(SearchKafkaRecord(encoded(event("new", "ProductCreated", "{\"status\":\"ACTIVE\"}"))), SearchKafkaRecord(encoded(event("duplicate", "ProductDeleted", "{}")))))
        SearchConsumer(config, repository, writer) { kafka }.consumeOnce()

        assertEquals(listOf("new"), repository.recordedIds)
        assertEquals(listOf("product-1"), writer.indexedIds)
        assertEquals(1, kafka.commitCalls)
        assertEquals(listOf("catalog-events"), kafka.subscribedTopics)

        val emptyKafka = FakeKafkaConsumer(emptyList())
        SearchConsumer(config, repository, writer) { emptyKafka }.consumeOnce()
        assertEquals(0, emptyKafka.commitCalls)
    }

    @Test
    fun `event types dispatch indexing deletion price updates and no-op events`() {
        val repository = FakeInboxStore()
        val writer = FakeSearchWriter()
        val consumer = SearchConsumer(config, repository, writer) { FakeKafkaConsumer(emptyList()) }

        consumer.apply(event("created", "ProductCreated", "{}"))
        consumer.apply(event("created-active", "ProductCreated", "{\"status\":\"ACTIVE\"}"))
        consumer.apply(event("updated", "ProductUpdated", "{\"status\":\"ACTIVE\"}"))
        consumer.apply(event("unpublished", "ProductUnpublished", "{}"))
        consumer.apply(event("inactive", "ProductArchived", "{\"status\":\"INACTIVE\"}"))
        consumer.apply(event("archived", "ProductPublished", "{\"status\":\"ARCHIVED\"}"))
        consumer.apply(event("deleted", "ProductDeleted", "{}"))
        consumer.apply(event("price-1", "PriceUpdated", "{\"productId\":\"product-1\",\"unitMinor\":1250,\"priceId\":\"price-1\",\"version\":\"2\"}"))
        consumer.apply(event("price-2", "PriceUpdated", "{\"productId\":\"product-1\",\"variantId\":\"variant-1\",\"unitMinor\":1500,\"priceId\":\"price-1\",\"version\":\"3\"}"))
        consumer.apply(event("price-deleted", "PriceDeleted", "{}"))
        consumer.apply(event("category", "CategoryCreated", "{}"))
        consumer.apply(event("category-updated", "CategoryUpdated", "{}"))
        consumer.apply(event("unknown", "UnknownEvent", "{}"))

        assertEquals(listOf("product-1", "product-1", "product-1", "product-1"), writer.indexedIds)
        assertEquals(listOf("product-1", "product-1", "product-1"), writer.deletedIds)
        assertEquals(2, writer.priceUpdates.size)
        assertEquals("product-1", writer.priceUpdates[0].productId)
        assertEquals(null, writer.priceUpdates[0].variantId)
        assertEquals(1250, writer.priceUpdates[0].amount)
        assertEquals("price-1:2", writer.priceUpdates[0].version)
        assertEquals("variant-1", writer.priceUpdates[1].variantId)
        assertEquals(1500, writer.priceUpdates[1].amount)
        assertEquals("price-1:3", writer.priceUpdates[1].version)
    }

    @Test
    fun `unknown events with known type hashes are ignored safely`() {
        val writer = FakeSearchWriter()
        val consumer = SearchConsumer(config, FakeInboxStore(), writer) { FakeKafkaConsumer(emptyList()) }
        val knownTypes = listOf(
            "ProductCreated",
            "ProductUpdated",
            "ProductPublished",
            "ProductUnpublished",
            "ProductArchived",
            "ProductDeleted",
            "PriceUpdated",
            "PriceDeleted",
            "CategoryUpdated",
            "CategoryCreated",
        )

        knownTypes.forEachIndexed { index, knownType ->
            val collision = knownType.toCharArray().let { chars ->
                chars[0] = (chars[0].code + 1).toChar()
                chars[1] = (chars[1].code - 31).toChar()
                String(chars)
            }
            assertEquals(knownType.hashCode(), collision.hashCode())
            assertTrue(collision != knownType)
            consumer.apply(event("collision-$index", collision, "{}"))
        }

        assertTrue(writer.indexedIds.isEmpty())
        assertTrue(writer.deletedIds.isEmpty())
        assertTrue(writer.priceUpdates.isEmpty())
    }

    @Test
    fun `price event rejects missing numeric fields`() {
        val consumer = SearchConsumer(config, FakeInboxStore(), FakeSearchWriter()) { FakeKafkaConsumer(emptyList()) }
        assertFailsWith<IllegalStateException> { consumer.apply(event("bad-amount", "PriceUpdated", "{\"productId\":\"product-1\",\"priceId\":\"price-1\",\"version\":\"1\",\"unitMinor\":\"bad\"}")) }
        assertFailsWith<IllegalStateException> { consumer.apply(event("bad-version", "PriceUpdated", "{\"productId\":\"product-1\",\"priceId\":\"price-1\",\"version\":\"bad\",\"unitMinor\":\"10\"}")) }
        assertFailsWith<NullPointerException> { consumer.apply(event("missing-amount", "PriceUpdated", "{\"productId\":\"product-1\",\"priceId\":\"price-1\",\"version\":\"1\"}")) }
        assertFailsWith<NullPointerException> { consumer.apply(event("missing-version", "PriceUpdated", "{\"productId\":\"product-1\",\"priceId\":\"price-1\",\"unitMinor\":\"10\"}")) }
        assertFailsWith<NullPointerException> { consumer.apply(event("missing-product", "PriceUpdated", "{\"priceId\":\"price-1\",\"version\":\"1\",\"unitMinor\":\"10\"}")) }
        assertFailsWith<NullPointerException> { consumer.apply(event("missing-price", "PriceUpdated", "{\"productId\":\"product-1\",\"version\":\"1\",\"unitMinor\":\"10\"}")) }
    }

    @Test
    fun `consumer worker retries after polling failure and closes its active consumer`() {
        val kafka = FakeKafkaConsumer(emptyList(), pollFailure = IllegalStateException("poll failed"))
        val consumer = SearchConsumer(config, FakeInboxStore(), FakeSearchWriter()) { kafka }

        consumer.start()

        assertTrue(kafka.pollStarted.await(2, TimeUnit.SECONDS))
        consumer.close()
        assertTrue(kafka.closedLatch.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `consumer worker processes a successful poll before cancellation`() {
        val repository = FakeInboxStore()
        val writer = FakeSearchWriter()
        val kafka = FakeKafkaConsumer(listOf(SearchKafkaRecord(encoded(event("worker-event", "ProductCreated", "{\"status\":\"ACTIVE\"}")))))
        val consumer = SearchConsumer(config, repository, writer) { kafka }

        consumer.start()

        assertTrue(kafka.commitLatch.await(2, TimeUnit.SECONDS))
        consumer.close()

        assertEquals(listOf("worker-event"), repository.recordedIds)
        assertEquals(listOf("product-1"), writer.indexedIds)
        assertTrue(kafka.closedLatch.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `closing an idle consumer is safe before its worker starts`() {
        val kafka = FakeKafkaConsumer(emptyList())
        SearchConsumer(config, FakeInboxStore(), FakeSearchWriter()) { kafka }.close()
        assertTrue(!kafka.closed)
    }

    @Test
    fun `consumer restarted after close does not subscribe or poll`() {
        val kafka = FakeKafkaConsumer(emptyList())
        val consumer = SearchConsumer(config, FakeInboxStore(), FakeSearchWriter()) { kafka }
        consumer.close()

        consumer.start()

        assertTrue(kafka.subscribedTopics.isEmpty())
        assertTrue(!kafka.pollStarted.await(25, TimeUnit.MILLISECONDS))
        consumer.close()
    }

    private fun event(id: String, type: String, payload: String) = EventEnvelope(id, type, 1, "2026-08-21T00:00:00Z", "catalog-service", "tenant-1", "product", "product-1", "correlation-1", payloadJson = payload)
    private fun encoded(event: EventEnvelope) = json.encodeToString(event)

    private class FakeInboxStore(val processedIds: MutableSet<String> = mutableSetOf()) : SearchInboxStore {
        val recordedIds = mutableListOf<String>()
        override fun processed(eventId: String) = eventId in processedIds
        override fun record(eventId: String, eventType: String, aggregateId: String): Int { recordedIds += eventId; processedIds += eventId; return 1 }
    }

    private class FakeSearchWriter : SearchIndexWriter {
        val indexedIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()
        val priceUpdates = mutableListOf<PriceUpdate>()
        override fun indexProduct(id: String, document: JsonObject) { indexedIds += id }
        override fun updatePrice(productId: String, variantId: String?, priceMinor: Long, priceVersion: String) { priceUpdates += PriceUpdate(productId, variantId, priceMinor, priceVersion) }
        override fun deleteProduct(id: String) { deletedIds += id }
    }

    private data class PriceUpdate(val productId: String, val variantId: String?, val amount: Long, val version: String)

    private class FakeKafkaConsumer(
        private val records: List<SearchKafkaRecord>,
        private val pollFailure: Throwable? = null,
    ) : SearchKafkaConsumer {
        var subscribedTopics: List<String> = emptyList()
        var commitCalls = 0
        var closed = false
        val pollStarted = CountDownLatch(1)
        val commitLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        override fun subscribe(topics: List<String>) { subscribedTopics = topics }
        override fun poll(timeout: Duration): List<SearchKafkaRecord> {
            pollStarted.countDown()
            pollFailure?.let { throw it }
            return records
        }
        override fun commitSync() { commitCalls++; commitLatch.countDown() }
        override fun close() { closed = true; closedLatch.countDown() }
    }
}
