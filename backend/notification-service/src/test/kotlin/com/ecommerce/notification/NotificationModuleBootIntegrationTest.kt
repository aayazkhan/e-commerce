package com.ecommerce.notification

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
 * implementation this follows. Kafka consumer/provider connections are lazy
 * background loops (same pattern as every consumer in this codebase), so a fake
 * bootstrap-servers/provider endpoint is safe for a boot-only check.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationModuleBootIntegrationTest {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `real module wiring boots against a live database`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "notification.database.url" to postgres.jdbcUrl,
                "notification.database.username" to postgres.username,
                "notification.database.password" to postgres.password,
                "notification.database.maximumPoolSize" to "5",
                "notification.database.connectionTimeoutMillis" to "5000",
                "notification.defaultChannels" to "email",
                "notification.jwt.issuer" to "issuer",
                "notification.jwt.audience" to "audience",
                "notification.jwt.keys" to "kid-1=secret",
                "notification.kafka.bootstrapServers" to "localhost:0",
                "notification.kafka.topics" to "order.events",
                "notification.kafka.groupId" to "notification-consumer-test",
                "notification.providers.apns.apiKey" to "test-key",
                "notification.providers.apns.endpoint" to "http://localhost:0",
                "notification.providers.email.apiKey" to "test-key",
                "notification.providers.email.endpoint" to "http://localhost:0",
                "notification.providers.fcm.apiKey" to "test-key",
                "notification.providers.fcm.endpoint" to "http://localhost:0",
                "notification.providers.sms.apiKey" to "test-key",
                "notification.providers.sms.endpoint" to "http://localhost:0",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
