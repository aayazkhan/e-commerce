package com.ecommerce.platform.service

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Instant
import java.util.Properties

data class ServiceOutboxRecord(
    val id: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val correlationId: String,
    val payloadJson: String,
)

interface ServiceOutboxStore {
    fun unpublished(limit: Int): List<ServiceOutboxRecord>
    fun markPublished(ids: List<String>, publishedAt: Instant)
}

data class ServiceKafkaConfig(val bootstrapServers: String, val topic: String, val tenantId: String)

interface OutboxProducer {
    fun send(topic: String, key: String, value: String)
    fun flush()
    fun close()
}

private class KafkaOutboxProducer(bootstrapServers: String) : OutboxProducer {
    private val producer = KafkaProducer<String, String>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
        put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd")
    })

    override fun send(topic: String, key: String, value: String) { producer.send(ProducerRecord(topic, key, value)).get() }
    override fun flush() = producer.flush()
    override fun close() = producer.close()
}

class KafkaOutboxPublisher(
    private val store: ServiceOutboxStore,
    config: ServiceKafkaConfig,
    private val producerName: String,
    private val producer: OutboxProducer = KafkaOutboxProducer(config.bootstrapServers),
    private val pause: suspend () -> Unit = { delay(250) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val topic = config.topic
    private val tenantId = config.tenantId
    private val json = Json { encodeDefaults = true }
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(dispatcher) {
            while (isActive) {
                runCatching { publishOnce() }
                pause()
            }
        }
    }

    fun publishOnce() {
        val records = store.unpublished(100)
        if (records.isEmpty()) return
        val published = records.map { record ->
            val envelope = EventEnvelope(record.id, record.eventType, record.schemaVersion, record.occurredAt.toString(), producerName, tenantId, record.aggregateType, record.aggregateId, record.correlationId, payloadJson = record.payloadJson)
            producer.send(topic, record.aggregateId, json.encodeToString(EventEnvelope.serializer(), envelope))
            record.id
        }
        store.markPublished(published, Instant.now())
    }

    override fun close() {
        job?.cancel()
        producer.flush()
        producer.close()
    }
}

fun newOutboxId(): String = CommerceId.new("evt").value
