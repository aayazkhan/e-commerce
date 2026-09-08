package com.ecommerce.payment

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
 * Boots the REAL [Application.module] -- the config-driven DB/Kafka/JWT wiring that a
 * plain unit test cannot reach, because [com.ecommerce.platform.service.ServiceDatabase]
 * makes a live JDBC connection in its constructor. This is the only test that exercises
 * that wiring; see docs/phase10/coverage-exceptions.md for why it's a required
 * `@Tag("integration")` test rather than a unit test, and how its coverage counts.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentModuleBootIntegrationTest {
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
                "payment.database.url" to postgres.jdbcUrl,
                "payment.database.username" to postgres.username,
                "payment.database.password" to postgres.password,
                "payment.database.maximumPoolSize" to "5",
                "payment.database.connectionTimeoutMillis" to "5000",
                "payment.provider.baseUrl" to "http://localhost:0",
                "payment.provider.apiKey" to "test-key",
                "payment.provider.webhookSecret" to "test-webhook-secret",
                "payment.jwt.issuer" to "issuer",
                "payment.jwt.audience" to "audience",
                "payment.jwt.keys" to "kid-1=secret",
                "payment.kafka.bootstrapServers" to kafka.bootstrapServers,
                "payment.kafka.topic" to "payment.events",
                "payment.kafka.tenantId" to "tenant-test",
                "payment.reconciliationIntervalSeconds" to "3600",
                "payment.internalToken" to "internal-test-token",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
