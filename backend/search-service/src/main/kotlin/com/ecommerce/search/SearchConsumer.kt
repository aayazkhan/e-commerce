package com.ecommerce.search

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties

internal interface SearchInboxStore {
    fun processed(eventId: String): Boolean
    fun record(eventId: String, eventType: String, aggregateId: String): Int
}

internal interface SearchIndexWriter {
    fun indexProduct(id: String, document: kotlinx.serialization.json.JsonObject)
    fun updatePrice(productId: String, variantId: String?, priceMinor: Long, priceVersion: String)
    fun deleteProduct(id: String)
}

internal data class SearchKafkaRecord(val value: String)

internal interface SearchKafkaConsumer : AutoCloseable {
    fun subscribe(topics: List<String>)
    fun poll(timeout: Duration): List<SearchKafkaRecord>
    fun commitSync()
}

internal fun interface SearchKafkaConsumerFactory {
    fun create(config: SearchConsumerConfig): SearchKafkaConsumer
}

private object DefaultSearchKafkaConsumerFactory : SearchKafkaConsumerFactory {
    override fun create(config: SearchConsumerConfig): SearchKafkaConsumer {
        val consumer = KafkaConsumer<String, String>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        })
        return object : SearchKafkaConsumer {
            override fun subscribe(topics: List<String>) = consumer.subscribe(topics)
            override fun poll(timeout: Duration) = consumer.poll(timeout).map { SearchKafkaRecord(it.value()) }
            override fun commitSync() = consumer.commitSync()
            override fun close() = consumer.close()
        }
    }
}

internal class SearchConsumer(
    private val config: SearchConsumerConfig,
    private val repository: SearchInboxStore,
    private val client: SearchIndexWriter,
    private val factory: SearchKafkaConsumerFactory = DefaultSearchKafkaConsumerFactory,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() { job = scope.launch { runLoop() } }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching { consumeOnce() }.onFailure { delay(1000) }
        }
    }

    internal fun consumeOnce() {
        factory.create(config).use { consumer ->
            consumer.subscribe(config.topics)
            val records = consumer.poll(Duration.ofMillis(500))
            records.forEach { record ->
                val event = json.decodeFromString<EventEnvelope>(record.value)
                if (!repository.processed(event.eventId)) {
                    apply(event)
                    repository.record(event.eventId, event.eventType, event.aggregateId)
                }
            }
            if (records.isNotEmpty()) consumer.commitSync()
        }
    }

    internal fun apply(event: EventEnvelope) {
        when (event.eventType) {
            "ProductCreated", "ProductUpdated", "ProductPublished", "ProductUnpublished", "ProductArchived" -> {
                val payload = json.parseToJsonElement(event.payloadJson).jsonObject
                if (payload["status"]?.jsonPrimitive?.content in setOf("ARCHIVED", "INACTIVE")) client.deleteProduct(event.aggregateId)
                else client.indexProduct(event.aggregateId, payload)
            }
            "ProductDeleted" -> client.deleteProduct(event.aggregateId)
            "PriceUpdated" -> {
                val payload = json.parseToJsonElement(event.payloadJson).jsonObject
                val amount = payload["unitMinor"]!!.jsonPrimitive.content.toLongOrNull() ?: error("Missing price amount")
                val version = payload["version"]!!.jsonPrimitive.content.toLongOrNull() ?: error("Missing price version")
                client.updatePrice(payload["productId"]!!.jsonPrimitive.content, payload["variantId"]?.jsonPrimitive?.content, amount, "${payload["priceId"]!!.jsonPrimitive.content}:$version")
            }
            "PriceDeleted" -> Unit
            "CategoryUpdated", "CategoryCreated" -> Unit
        }
    }

    override fun close() { job?.cancel(); scope.cancel() }
}

data class SearchConsumerConfig(val bootstrapServers: String, val topics: List<String>, val groupId: String)
