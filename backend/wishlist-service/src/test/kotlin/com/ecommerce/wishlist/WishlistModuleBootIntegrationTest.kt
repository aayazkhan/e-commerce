package com.ecommerce.wishlist

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
class WishlistModuleBootIntegrationTest {
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
                "wishlist.database.url" to postgres.jdbcUrl,
                "wishlist.database.username" to postgres.username,
                "wishlist.database.password" to postgres.password,
                "wishlist.database.maximumPoolSize" to "5",
                "wishlist.database.connectionTimeoutMillis" to "5000",
                "wishlist.inventoryBaseUrl" to "http://localhost:0",
                "wishlist.pricingBaseUrl" to "http://localhost:0",
                "wishlist.jwt.issuer" to "issuer",
                "wishlist.jwt.audience" to "audience",
                "wishlist.jwt.keys" to "kid-1=secret",
                "wishlist.kafka.bootstrapServers" to kafka.bootstrapServers,
                "wishlist.kafka.topic" to "wishlist.events",
                "wishlist.kafka.tenantId" to "tenant-test",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
