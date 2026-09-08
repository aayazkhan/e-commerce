package com.ecommerce.seller

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the REAL [Application.module] against live Postgres/Redis/Kafka -- see
 * docs/phase10/coverage-exceptions.md for why this is a required @Tag("integration")
 * test rather than a unit test, and payment-service's version for the reference
 * implementation this follows. seller-service both produces (outbox: topic/tenantId)
 * and consumes (groupId/topics) Kafka, both against the same broker.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SellerModuleBootIntegrationTest {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmField val redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
        @Container @JvmField val kafka = KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.0").asCompatibleSubstituteFor("confluentinc/cp-kafka"),
        )
    }

    @Test
    fun `real module wiring boots against a live database, redis, and kafka broker`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "seller.database.url" to postgres.jdbcUrl,
                "seller.database.username" to postgres.username,
                "seller.database.password" to postgres.password,
                "seller.database.maximumPoolSize" to "5",
                "seller.database.connectionTimeoutMillis" to "5000",
                "seller.analyticsUrl" to "http://localhost:0",
                "seller.catalogUrl" to "http://localhost:0",
                "seller.internalServiceToken" to "internal-test-token",
                "seller.inventoryUrl" to "http://localhost:0",
                "seller.promotionUrl" to "http://localhost:0",
                "seller.jwt.issuer" to "issuer",
                "seller.jwt.audience" to "audience",
                "seller.jwt.keys" to "kid-1=secret",
                "seller.kafka.bootstrapServers" to kafka.bootstrapServers,
                "seller.kafka.topic" to "seller.events",
                "seller.kafka.tenantId" to "tenant-test",
                "seller.kafka.groupId" to "seller-consumer-test",
                "seller.kafka.topics" to "order.events",
                "seller.redis.url" to "redis://${redis.host}:${redis.getMappedPort(6379)}",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
