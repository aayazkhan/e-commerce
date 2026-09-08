package com.ecommerce.media

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
 * implementation this follows. S3 storage itself is not exercised here (media.storage.endpoint
 * is optional and left unset), since /health/ready does not touch object storage.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaModuleBootIntegrationTest {
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
                "media.database.url" to postgres.jdbcUrl,
                "media.database.username" to postgres.username,
                "media.database.password" to postgres.password,
                "media.jwt.issuer" to "issuer",
                "media.jwt.audience" to "audience",
                "media.jwt.keys" to "kid-1=secret",
                "media.kafka.bootstrapServers" to kafka.bootstrapServers,
                "media.kafka.topic" to "media.events",
                "media.kafka.tenantId" to "tenant-test",
                "media.storage.bucket" to "commerce-media-test",
                "media.storage.region" to "us-east-1",
                "media.storage.maxBytes" to "10485760",
                "media.storage.presignMinutes" to "15",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
