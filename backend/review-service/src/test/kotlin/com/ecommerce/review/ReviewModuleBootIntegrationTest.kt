package com.ecommerce.review

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the REAL [Application.module] against a live database -- see
 * docs/phase10/coverage-exceptions.md for why this is a required @Tag("integration")
 * test rather than a unit test, and payment-service's version for the reference
 * implementation this follows. The Kafka consumer connects lazily in a background
 * loop, so a fake bootstrap-servers value is safe for a boot-only check.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewModuleBootIntegrationTest {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `real module wiring boots against a live database`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "review.database.url" to postgres.jdbcUrl,
                "review.database.username" to postgres.username,
                "review.database.password" to postgres.password,
                "review.database.maximumPoolSize" to "5",
                "review.database.connectionTimeoutMillis" to "5000",
                "review.jwt.issuer" to "issuer",
                "review.jwt.audience" to "audience",
                "review.jwt.keys" to "kid-1=secret",
                "review.kafka.bootstrapServers" to "localhost:0",
                "review.kafka.topics" to "order.events",
                "review.kafka.groupId" to "review-consumer-test",
                "review.moderation.autoPublish" to "false",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
