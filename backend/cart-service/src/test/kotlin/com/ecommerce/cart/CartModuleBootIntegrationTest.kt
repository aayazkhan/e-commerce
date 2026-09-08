package com.ecommerce.cart

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
 * implementation this follows.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartModuleBootIntegrationTest {
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
                "cart.database.url" to postgres.jdbcUrl,
                "cart.database.username" to postgres.username,
                "cart.database.password" to postgres.password,
                "cart.database.maximumPoolSize" to "5",
                "cart.database.connectionTimeoutMillis" to "5000",
                "cart.guestExpirationSeconds" to "3600",
                "cart.userExpirationSeconds" to "86400",
                "cart.pricingBaseUrl" to "http://localhost:0",
                "cart.inventoryBaseUrl" to "http://localhost:0",
                "cart.jwt.issuer" to "issuer",
                "cart.jwt.audience" to "audience",
                "cart.jwt.keys" to "kid-1=secret",
                "cart.kafka.bootstrapServers" to kafka.bootstrapServers,
                "cart.kafka.topic" to "cart.events",
                "cart.kafka.tenantId" to "tenant-test",
                "cart.redis.url" to "redis://${redis.host}:${redis.getMappedPort(6379)}",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
