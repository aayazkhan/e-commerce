package com.ecommerce.checkout

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
class CheckoutModuleBootIntegrationTest {
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
                "checkout.database.url" to postgres.jdbcUrl,
                "checkout.database.username" to postgres.username,
                "checkout.database.password" to postgres.password,
                "checkout.database.maximumPoolSize" to "5",
                "checkout.database.connectionTimeoutMillis" to "5000",
                "checkout.internalToken" to "internal-test-token",
                "checkout.jwt.issuer" to "issuer",
                "checkout.jwt.audience" to "audience",
                "checkout.jwt.keys" to "kid-1=secret",
                "checkout.kafka.bootstrapServers" to kafka.bootstrapServers,
                "checkout.kafka.topic" to "checkout.events",
                "checkout.kafka.tenantId" to "tenant-test",
                "checkout.cartBaseUrl" to "http://localhost:0",
                "checkout.catalogBaseUrl" to "http://localhost:0",
                "checkout.identityBaseUrl" to "http://localhost:0",
                "checkout.inventoryBaseUrl" to "http://localhost:0",
                "checkout.orderBaseUrl" to "http://localhost:0",
                "checkout.paymentBaseUrl" to "http://localhost:0",
                "checkout.pricingBaseUrl" to "http://localhost:0",
                "checkout.promotionBaseUrl" to "http://localhost:0",
                "checkout.shippingBaseUrl" to "http://localhost:0",
                "checkout.warehouseId" to "warehouse-test",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
