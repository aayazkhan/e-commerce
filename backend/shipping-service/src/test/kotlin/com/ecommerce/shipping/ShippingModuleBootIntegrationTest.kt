package com.ecommerce.shipping

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the REAL [Application.module] against live Postgres/Kafka -- see
 * docs/phase10/coverage-exceptions.md for why this is a required @Tag("integration")
 * test rather than a unit test, and payment-service's version for the reference
 * implementation this follows.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShippingModuleBootIntegrationTest {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmField val kafka = KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.0").asCompatibleSubstituteFor("confluentinc/cp-kafka"),
        )
    }

    @Test
    fun `real module wiring boots against a live database and kafka broker`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "shipping.database.url" to postgres.jdbcUrl,
                "shipping.database.username" to postgres.username,
                "shipping.database.password" to postgres.password,
                "shipping.database.maximumPoolSize" to "5",
                "shipping.database.connectionTimeoutMillis" to "5000",
                "shipping.internalToken" to "internal-test-token",
                "shipping.jwt.issuer" to "issuer",
                "shipping.jwt.audience" to "audience",
                "shipping.jwt.keys" to "kid-1=secret",
                "shipping.kafka.bootstrapServers" to kafka.bootstrapServers,
                "shipping.kafka.topic" to "shipping.events",
                "shipping.kafka.tenantId" to "tenant-test",
                "shipping.provider.baseUrl" to "http://localhost:0",
                "shipping.provider.apiKey" to "test-key",
                "shipping.provider.webhookSecret" to "test-webhook-secret",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
