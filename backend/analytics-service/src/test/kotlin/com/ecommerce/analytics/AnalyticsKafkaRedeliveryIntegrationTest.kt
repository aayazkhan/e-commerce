package com.ecommerce.analytics

import com.ecommerce.platform.kafka.EventEnvelope
import com.ecommerce.platform.kafka.KafkaConsumerWorker
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.Properties
import kotlin.test.assertEquals

/**
 * The unit-level dedup test (AnalyticsRepositoryBehaviorTest's "ignores duplicate events") proves
 * AnalyticsRepository.accept() is idempotent given two calls with the same event ID. What it can't
 * prove is that the *real* Kafka wiring -- KafkaConsumerWorker's manual-commit poll loop -- actually
 * reaches that dedup path when the SAME message is genuinely redelivered by a live broker (e.g. a
 * consumer crash before offset commit). This test publishes the identical event twice to a real
 * Testcontainers Kafka topic and asserts the repository only applied it once, closing the one row
 * in docs/phase10/test-matrix.md's acceptance matrix that unit tests structurally cannot cover.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsKafkaRedeliveryIntegrationTest {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmField val kafka = KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.0").asCompatibleSubstituteFor("confluentinc/cp-kafka"),
        )
    }

    @Test
    fun `a duplicated broker delivery of the same event is applied only once`() = runBlocking {
        val database = ServiceDatabase(
            ServiceDatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password),
            "classpath:db/migration",
        )
        val repository = AnalyticsRepository(database.dataSource(), Json { encodeDefaults = true })
        val topic = "analytics.events.redelivery-test"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = KafkaConsumerWorker(
            bootstrapServers = kafka.bootstrapServers,
            groupId = "analytics-redelivery-test",
            topics = listOf(topic),
            consumerName = "analytics-redelivery-test",
            onEvent = repository::accept,
            onFailure = { event, error -> repository.dlq(event, error) },
        )

        try {
            worker.start(scope)

            val event = EventEnvelope(
                eventId = "evt-redelivery-1",
                eventType = "ProductViewed",
                schemaVersion = 1,
                occurredAt = "2026-08-20T10:15:00Z",
                producer = "catalog-service",
                tenantId = "tenant-1",
                aggregateType = "Product",
                aggregateId = "product-1",
                correlationId = "corr-1",
                payloadJson = "{}",
            )
            val payload = Json.encodeToString(EventEnvelope.serializer(), event)
            publishTwice(topic, event.eventId, payload)

            val deadline = System.currentTimeMillis() + 30_000
            var eventsSeen = 0L
            while (System.currentTimeMillis() < deadline) {
                eventsSeen = repository.summary(null, null).events
                if (worker.recordsObserved.get() >= 2) break
                delay(500)
            }
            // Give the second (duplicate) delivery a moment to reach the consumer and be deduped.
            delay(2_000)
            eventsSeen = repository.summary(null, null).events

            assertEquals(1L, eventsSeen, "the duplicated broker delivery must not be double-counted")
        } finally {
            worker.close()
            scope.cancel()
            database.close()
        }
    }

    private fun publishTwice(topic: String, key: String, value: String) {
        val producer = KafkaProducer<String, String>(Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        })
        producer.use {
            it.send(ProducerRecord(topic, key, value)).get()
            it.send(ProducerRecord(topic, key, value)).get()
            it.flush()
        }
    }
}
