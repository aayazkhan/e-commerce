package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.config.KafkaConfig
import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Instant
import java.util.Properties

interface IdentityOutboxStore {
    fun unpublishedOutbox(limit: Int = 100): List<OutboxRecord>
    fun markOutboxPublished(ids: Collection<String>, publishedAt: Instant)
}

interface IdentityOutboxProducer {
    fun send(topic: String, key: String, value: String)
    fun flush()
    fun close()
}

private class KafkaIdentityOutboxProducer(bootstrapServers: String) : IdentityOutboxProducer {
    private val producer = KafkaProducer<String, String>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
        put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd")
        put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5")
    })

    override fun send(topic: String, key: String, value: String) { producer.send(ProducerRecord(topic, key, value)).get() }
    override fun flush() = producer.flush()
    override fun close() = producer.close()
}

class OutboxPublisher(
    private val repository: IdentityOutboxStore,
    config: KafkaConfig,
    private val producer: IdentityOutboxProducer = KafkaIdentityOutboxProducer(config.bootstrapServers),
) : AutoCloseable {
    private val topic = config.topic
    private val tenantId = config.tenantId
    private val json = Json { encodeDefaults = true }
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { publishOnce() }
                delay(250)
            }
        }
    }

    fun publishOnce() {
        val events = repository.unpublishedOutbox(100)
        if (events.isEmpty()) return
        val published = events.map { event ->
            val envelope = EventEnvelope(event.id, event.eventType, event.schemaVersion, event.occurredAt.toString(), "identity-service", tenantId, event.aggregateType, event.aggregateId, event.correlationId, payloadJson = event.payloadJson)
            producer.send(topic, event.aggregateId, json.encodeToString(EventEnvelope.serializer(), envelope))
            event.id
        }
        repository.markOutboxPublished(published, Instant.now())
    }

    override fun close() {
        job?.cancel()
        producer.flush()
        producer.close()
    }
}
