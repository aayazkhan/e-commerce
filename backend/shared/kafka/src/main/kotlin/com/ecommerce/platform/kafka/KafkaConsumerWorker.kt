package com.ecommerce.platform.kafka

import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Manual-commit consumer. Handlers must durably persist the event before returning. */
interface KafkaConsumerClient : AutoCloseable {
    fun subscribe(topics: List<String>)
    fun poll(timeout: Duration): ConsumerRecords<String, String>
    fun assignment(): Set<TopicPartition>
    fun endOffsets(partitions: Set<TopicPartition>): Map<TopicPartition, Long>
    fun position(partition: TopicPartition): Long
    fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>)
    fun wakeup()
    fun close(timeout: Duration)
    override fun close() = close(Duration.ofSeconds(3))
}

interface KafkaProducerClient : AutoCloseable {
    fun send(topic: String, key: String, value: String)
    fun close(timeout: Duration)
    override fun close() = close(Duration.ofSeconds(3))
}

internal class DefaultKafkaConsumerClient(
    bootstrapServers: String,
    groupId: String,
    consumerName: String,
    delegate: KafkaConsumer<String, String>? = null,
) : KafkaConsumerClient {
    private val delegate = delegate ?: KafkaConsumer<String, String>(Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.CLIENT_ID_CONFIG, consumerName)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100")
    })

    override fun subscribe(topics: List<String>) = delegate.subscribe(topics)
    override fun poll(timeout: Duration) = delegate.poll(timeout)
    override fun assignment() = delegate.assignment()
    override fun endOffsets(partitions: Set<TopicPartition>) = delegate.endOffsets(partitions)
    override fun position(partition: TopicPartition) = delegate.position(partition)
    override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>) = delegate.commitSync(offsets)
    override fun wakeup() = delegate.wakeup()
    override fun close(timeout: Duration) = delegate.close(timeout)
}

internal class DefaultKafkaProducerClient(
    bootstrapServers: String,
    delegate: KafkaProducer<String, String>? = null,
) : KafkaProducerClient {
    private val delegate = delegate ?: KafkaProducer<String, String>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
        put(ProducerConfig.ACKS_CONFIG, "all")
    })

    override fun send(topic: String, key: String, value: String) {
        delegate.send(org.apache.kafka.clients.producer.ProducerRecord(topic, key, value)).get()
    }

    override fun close(timeout: Duration) = delegate.close(timeout)
}

class KafkaConsumerWorker(
    private val bootstrapServers: String,
    private val groupId: String,
    private val topics: List<String>,
    private val consumerName: String,
    private val onEvent: (EventEnvelope) -> Unit,
    private val onFailure: (EventEnvelope?, Throwable) -> Unit,
    private val consumer: KafkaConsumerClient = DefaultKafkaConsumerClient(bootstrapServers, groupId, consumerName),
    private val producer: KafkaProducerClient = DefaultKafkaProducerClient(bootstrapServers),
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private var job: Job? = null
    val recordsObserved = AtomicLong(0)
    val lastEventEpochMillis = AtomicLong(0)
    val failuresObserved = AtomicLong(0)
    val lagObserved = AtomicLong(0)

    fun start(scope: CoroutineScope) {
        require(topics.isNotEmpty()) { "At least one Kafka topic is required" }
        job = scope.launch(Dispatchers.IO) {
            try {
                consumer.subscribe(topics)
                while (isActive) {
                    val records = consumer.poll(Duration.ofMillis(500))
                    runCatching { lagObserved.set(consumer.assignment().sumOf { partition -> consumer.endOffsets(setOf(partition))[partition]!! - consumer.position(partition) }) }
                    for (record in records) {
                        var envelope: EventEnvelope? = null
                        var commitAllowed = true
                        try {
                            envelope = json.decodeFromString<EventEnvelope>(record.value())
                            onEvent(envelope)
                            recordsObserved.incrementAndGet()
                            lastEventEpochMillis.set(System.currentTimeMillis())
                        } catch (error: Throwable) {
                            failuresObserved.incrementAndGet()
                            commitAllowed = runCatching { onFailure(envelope, error); true }.getOrDefault(false)
                        } finally {
                            // A failed record is committed only after the service has recorded its DLQ entry.
                            if (commitAllowed) consumer.commitSync(mapOf(TopicPartition(record.topic(), record.partition()) to OffsetAndMetadata(record.offset() + 1)))
                        }
                    }
                }
            } catch (_: WakeupException) {
                // Normal shutdown.
            } catch (error: Throwable) {
                failuresObserved.incrementAndGet()
                runCatching { onFailure(null, error) }
            }
        }
    }

    fun publishDlq(topic: String, key: String, value: String) {
        producer.send(topic, key, value)
    }

    override fun close() {
        consumer.wakeup()
        job?.cancel()
        runCatching { consumer.close(Duration.ofSeconds(3)) }
        runCatching { producer.close(Duration.ofSeconds(3)) }
    }
}
