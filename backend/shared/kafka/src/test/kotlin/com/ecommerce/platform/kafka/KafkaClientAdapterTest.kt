package com.ecommerce.platform.kafka

import java.time.Duration
import java.util.Properties
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition

class KafkaClientAdapterTest {
    @Test
    fun `consumer adapter forwards subscription polling offsets and lifecycle`() {
        val partition = TopicPartition("orders", 2)
        val records = ConsumerRecords(
            mapOf(partition to listOf(ConsumerRecord("orders", 2, 7L, "order-7", "payload"))),
        )
        val delegate = StubConsumer(records, partition)
        val client = DefaultKafkaConsumerClient("unused", "group", "consumer", delegate)

        client.subscribe(listOf("orders"))
        assertEquals(listOf("orders"), delegate.subscribed)
        assertEquals(records, client.poll(Duration.ofMillis(1)))
        assertEquals(setOf(partition), client.assignment())
        assertEquals(mapOf(partition to 9L), client.endOffsets(setOf(partition)))
        assertEquals(7L, client.position(partition))
        client.commitSync(mapOf(partition to OffsetAndMetadata(8L)))
        client.wakeup()
        client.close(Duration.ofMillis(1))
        assertEquals(1, delegate.commits.size)
        assertEquals(true, delegate.woken)
        assertEquals(Duration.ofMillis(1), delegate.closedWith)
    }

    @Test
    fun `producer adapter forwards records and lifecycle`() {
        val delegate = StubProducer()
        val client = DefaultKafkaProducerClient("unused", delegate)

        client.send("orders.dlq", "order-7", "payload")
        client.close(Duration.ofMillis(1))

        assertEquals(listOf("orders.dlq:order-7:payload"), delegate.sent)
        assertEquals(Duration.ofMillis(1), delegate.closedWith)
    }

    @Test
    fun `default adapters build production clients and close without a broker call`() {
        DefaultKafkaConsumerClient("localhost:9092", "group", "consumer").close(Duration.ofMillis(1))
        DefaultKafkaProducerClient("localhost:9092").close(Duration.ofMillis(1))
    }

    @Test
    fun `client interface default close delegates to timed close`() {
        val consumer = StubConsumer(ConsumerRecords.empty(), TopicPartition("orders", 0))
        val producer = StubProducer()
        val consumerClient = DefaultKafkaConsumerClient("unused", "group", "consumer", consumer)
        val producerClient = DefaultKafkaProducerClient("unused", producer)

        DefaultCloseInvoker.closeConsumer(consumerClient)
        DefaultCloseInvoker.closeProducer(producerClient)

        assertEquals(Duration.ofSeconds(3), consumer.closedWith)
        assertEquals(Duration.ofSeconds(3), producer.closedWith)
    }

    private class StubConsumer(
        private val records: ConsumerRecords<String, String>,
        private val partition: TopicPartition,
    ) : KafkaConsumer<String, String>(consumerProperties()) {
        var subscribed: List<String> = emptyList()
        val commits = mutableListOf<Map<TopicPartition, OffsetAndMetadata>>()
        var woken = false
        var closedWith: Duration? = null

        override fun subscribe(topics: Collection<String>) {
            subscribed = topics.toList()
        }

        override fun poll(timeout: Duration) = records

        override fun assignment() = setOf(partition)

        override fun endOffsets(partitions: Collection<TopicPartition>) = partitions.associateWith { 9L }

        override fun position(partition: TopicPartition) = 7L

        override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>) {
            commits += offsets
        }

        override fun wakeup() {
            woken = true
        }

        override fun close(timeout: Duration) {
            closedWith = timeout
        }
    }

    private class StubProducer : KafkaProducer<String, String>(producerProperties()) {
        val sent = mutableListOf<String>()
        var closedWith: Duration? = null

        override fun send(record: ProducerRecord<String, String>): CompletableFuture<RecordMetadata> {
            sent += "${record.topic()}:${record.key()}:${record.value()}"
            return CompletableFuture.completedFuture(null)
        }

        override fun close(timeout: Duration) {
            closedWith = timeout
        }
    }

    private companion object {
        fun consumerProperties() = Properties().apply {
            put("bootstrap.servers", "localhost:9092")
            put("group.id", "test-group")
            put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        }

        fun producerProperties() = Properties().apply {
            put("bootstrap.servers", "localhost:9092")
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        }
    }
}
