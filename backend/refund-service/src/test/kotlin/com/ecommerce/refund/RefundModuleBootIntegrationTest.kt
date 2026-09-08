package com.ecommerce.refund

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
class RefundModuleBootIntegrationTest {
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
                "refund.database.url" to postgres.jdbcUrl,
                "refund.database.username" to postgres.username,
                "refund.database.password" to postgres.password,
                "refund.database.maximumPoolSize" to "5",
                "refund.database.connectionTimeoutMillis" to "5000",
                "refund.internalToken" to "internal-test-token",
                "refund.jwt.issuer" to "issuer",
                "refund.jwt.audience" to "audience",
                "refund.jwt.keys" to "kid-1=secret",
                "refund.kafka.bootstrapServers" to kafka.bootstrapServers,
                "refund.kafka.topic" to "refund.events",
                "refund.kafka.tenantId" to "tenant-test",
                "refund.paymentBaseUrl" to "http://localhost:0",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
