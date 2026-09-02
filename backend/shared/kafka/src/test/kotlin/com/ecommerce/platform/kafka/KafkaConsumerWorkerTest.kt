package com.ecommerce.platform.kafka

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KafkaConsumerWorkerTest {
    private val envelope = EventEnvelope("event-1", "OrderCreated", 1, "2026-08-20T00:00:00Z", "order-service", "tenant-1", "Order", "order-1", "corr-1", payloadJson = "{}")

    @Test
    fun `worker decodes events commits offsets records lag and publishes DLQ`() = runBlocking {
        val topicPartition = TopicPartition("orders", 0)
        val consumer = FakeConsumer(
            listOf(ConsumerRecords(mapOf(topicPartition to listOf(ConsumerRecord("orders", 0, 4L, "order-1", Json.encodeToString(envelope))))))
        )
        consumer.endOffset = 8
        val producer = FakeProducer()
        val observed = CountDownLatch(1)
        val worker = KafkaConsumerWorker(
            "unused", "group", listOf("orders"), "test-consumer",
            onEvent = { assertEquals(envelope, it); observed.countDown() },
            onFailure = { _, error -> error.printStackTrace() },
            consumer = consumer,
            producer = producer,
        )

        worker.start(this)
        assertTrue(observed.await(2, TimeUnit.SECONDS))
        worker.publishDlq("orders.dlq", "event-1", "payload")
        worker.close()

        assertEquals(1, worker.recordsObserved.get())
        assertEquals(4, worker.lagObserved.get())
        assertEquals(1, consumer.commits.size)
        assertEquals(5L, consumer.commits.single().values.single().offset())
        assertEquals(listOf("orders.dlq:event-1:payload"), producer.messages)
    }

    @Test
    fun `worker invokes failure handler for malformed and handler failures and avoids commit when DLQ fails`() = runBlocking {
        val malformedConsumer = FakeConsumer(listOf(ConsumerRecords(mapOf(TopicPartition("orders", 0) to listOf(ConsumerRecord("orders", 0, 1L, "k", "not-json"))))))
        var malformedEvent: EventEnvelope? = envelope
        // Use the injectable client for deterministic polling and no broker connection.
        val malformedWorker = KafkaConsumerWorker("unused", "group", listOf("orders"), "malformed", { error("not expected") }, { event, _ -> malformedEvent = event }, malformedConsumer, FakeProducer())
        malformedWorker.start(this)
        withContext(Dispatchers.Default) { while (malformedConsumer.commits.isEmpty() && malformedWorker.failuresObserved.get() == 0L) kotlinx.coroutines.yield() }
        malformedWorker.close()
        assertEquals(null, malformedEvent)
        assertEquals(1, malformedConsumer.commits.size)

        val handlerConsumer = FakeConsumer(
            listOf(ConsumerRecords(mapOf(TopicPartition("orders", 0) to listOf(ConsumerRecord("orders", 0, 2L, "k", Json.encodeToString(envelope))))))
        )
        var failedEvent: EventEnvelope? = null
        val handlerWorker = KafkaConsumerWorker("unused", "group", listOf("orders"), "handler", { error("handler failed") }, { event, _ -> failedEvent = event }, handlerConsumer, FakeProducer())
        handlerWorker.start(this)
        while (handlerConsumer.commits.isEmpty() && handlerWorker.failuresObserved.get() == 0L) kotlinx.coroutines.yield()
        handlerWorker.close()
        assertEquals(envelope, failedEvent)
        assertEquals(1, handlerConsumer.commits.size)

        val dlqFailureConsumer = FakeConsumer(listOf(ConsumerRecords(mapOf(TopicPartition("orders", 0) to listOf(ConsumerRecord("orders", 0, 3L, "k", "not-json"))))))
        val dlqFailureWorker = KafkaConsumerWorker("unused", "group", listOf("orders"), "dlq-failure", { error("not expected") }, { _, _ -> error("DLQ unavailable") }, dlqFailureConsumer, FakeProducer())
        dlqFailureWorker.start(this)
        while (dlqFailureWorker.failuresObserved.get() == 0L) kotlinx.coroutines.yield()
        dlqFailureWorker.close()
        assertTrue(dlqFailureConsumer.commits.isEmpty())
    }

    @Test
    fun `worker handles lag metric failures outer poll failures and empty topic validation`() = runBlocking {
        val lagFailureConsumer = FakeConsumer(emptyList()).also { it.failEndOffsets = true }
        val lagWorker = KafkaConsumerWorker("unused", "group", listOf("orders"), "lag-failure", {}, { _, _ -> }, lagFailureConsumer, FakeProducer())
        lagWorker.start(this)
        while (!lagFailureConsumer.polled) kotlinx.coroutines.yield()
        lagWorker.close()

        val outerFailureConsumer = FakeConsumer(emptyList()).also { it.pollFailure = IllegalStateException("broker unavailable") }
        var outerFailure: Throwable? = null
        val outerFailureObserved = CountDownLatch(1)
        val outerWorker = KafkaConsumerWorker("unused", "group", listOf("orders"), "outer-failure", {}, { _, error -> outerFailure = error; outerFailureObserved.countDown() }, outerFailureConsumer, FakeProducer())
        outerWorker.start(this)
        assertTrue(outerFailureObserved.await(2, TimeUnit.SECONDS))
        outerWorker.close()
        assertEquals("broker unavailable", outerFailure?.message)

        val empty = KafkaConsumerWorker("unused", "group", emptyList(), "empty", {}, { _, _ -> }, FakeConsumer(emptyList()), FakeProducer())
        assertFailsWith<IllegalArgumentException> { empty.start(this) }
        empty.close()

        val handlerFailureConsumer = FakeConsumer(emptyList()).also { it.pollFailure = IllegalStateException("broker unavailable") }
        val handlerFailureWorker = KafkaConsumerWorker(
            "unused", "group", listOf("orders"), "outer-handler-failure", {}, { _, _ -> error("DLQ unavailable") },
            handlerFailureConsumer, FakeProducer(),
        )
        handlerFailureWorker.start(this)
        while (handlerFailureWorker.failuresObserved.get() == 0L) kotlinx.coroutines.yield()
        handlerFailureWorker.close()
    }

    @Test
    fun `worker default clients can be constructed and closed without connecting`() {
        KafkaConsumerWorker("localhost:9092", "group", listOf("orders"), "defaults", {}, { _, _ -> }).close()
    }

    @Test
    fun `worker stops polling when event handler cancels the worker context`() = runBlocking {
        val consumer = FakeConsumer(
            listOf(ConsumerRecords(mapOf(TopicPartition("orders", 0) to listOf(
                ConsumerRecord("orders", 0, 6L, "k", Json.encodeToString(envelope)),
            )))),
        )
        val observed = CountDownLatch(1)
        lateinit var worker: KafkaConsumerWorker
        worker = KafkaConsumerWorker(
            "unused", "group", listOf("orders"), "cancelled-handler",
            onEvent = {
                worker.close()
                observed.countDown()
            },
            onFailure = { _, _ -> },
            consumer = consumer,
            producer = FakeProducer(),
        )

        worker.start(this)
        assertTrue(observed.await(2, TimeUnit.SECONDS))
        while (worker.recordsObserved.get() == 0L) kotlinx.coroutines.yield()

        assertEquals(1L, worker.recordsObserved.get())
        assertEquals(1, consumer.commits.size)
    }

    @Test
    fun `worker keeps close resilient when kafka clients fail during shutdown`() {
        val consumer = FakeConsumer(emptyList()).also { it.closeFailure = IllegalStateException("consumer close failed") }
        val producer = FakeProducer().also { it.closeFailure = IllegalStateException("producer close failed") }
        val worker = KafkaConsumerWorker(
            "unused", "group", listOf("orders"), "close-failure", {}, { _, _ -> }, consumer, producer,
        )

        worker.close()
    }

    @Test
    fun `worker started on a cancelled scope does not subscribe`() {
        val consumer = FakeConsumer(emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.cancel()
        val worker = KafkaConsumerWorker("unused", "group", listOf("orders"), "cancelled-scope", {}, { _, _ -> }, consumer, FakeProducer())

        worker.start(scope)

        assertTrue(consumer.subscribedTopics.isEmpty())
        worker.close()
    }

    @Test
    fun `worker stops before polling when subscription cancels its scope`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var worker: KafkaConsumerWorker
        val consumer = FakeConsumer(emptyList()).also {
            it.onSubscribe = { scope.cancel() }
        }
        worker = KafkaConsumerWorker(
            "unused", "group", listOf("orders"), "cancelled-after-subscribe", {}, { _, _ -> },
            consumer, FakeProducer(),
        )

        worker.start(scope)
        while (scope.isActive) kotlinx.coroutines.yield()
        worker.close()

        assertEquals(listOf("orders"), consumer.subscribedTopics)
        assertTrue(!consumer.polled)
    }

    private class FakeConsumer(private val batches: List<ConsumerRecords<String, String>>) : KafkaConsumerClient {
        val commits = mutableListOf<Map<TopicPartition, OffsetAndMetadata>>()
        var endOffset = 0L
        var failEndOffsets = false
        var pollFailure: Throwable? = null
        var closeFailure: Throwable? = null
        var polled = false
        var onSubscribe: (() -> Unit)? = null
        var subscribedTopics: List<String> = emptyList()
        private var index = 0

        override fun subscribe(topics: List<String>) {
            subscribedTopics = topics
            onSubscribe?.invoke()
        }
        override fun poll(timeout: Duration): ConsumerRecords<String, String> {
            polled = true
            pollFailure?.let { throw it }
            return if (index < batches.size) batches[index++] else throw WakeupException()
        }
        override fun assignment() = setOf(TopicPartition("orders", 0))
        override fun endOffsets(partitions: Set<TopicPartition>): Map<TopicPartition, Long> {
            if (failEndOffsets) error("lag unavailable")
            return partitions.associateWith { endOffset }
        }
        override fun position(partition: TopicPartition) = 4L
        override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>) { commits += offsets }
        override fun wakeup() = Unit
        override fun close(timeout: Duration) { closeFailure?.let { throw it } }
    }

    private class FakeProducer : KafkaProducerClient {
        val messages = mutableListOf<String>()
        var closeFailure: Throwable? = null
        override fun send(topic: String, key: String, value: String) { messages += "$topic:$key:$value" }
        override fun close(timeout: Duration) { closeFailure?.let { throw it } }
    }
}
