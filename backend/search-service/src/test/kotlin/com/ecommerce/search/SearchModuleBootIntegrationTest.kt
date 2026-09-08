package com.ecommerce.search

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the REAL [Application.module] against live Postgres and OpenSearch -- see
 * docs/phase10/coverage-exceptions.md for why this is a required @Tag("integration")
 * test rather than a unit test. Unlike the other services, module() itself calls
 * `client.ensureAlias()` synchronously at boot, so this needs a genuinely reachable
 * OpenSearch, not just a fake URL -- a fake Kafka consumer group is fine since
 * SearchConsumer.start() runs its poll loop in the background, same as every other
 * service's KafkaOutboxPublisher.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchModuleBootIntegrationTest {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @Container @JvmField val opensearch = GenericContainer(DockerImageName.parse("opensearchproject/opensearch:2.19.1"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withExposedPorts(9200)
    }

    @Test
    fun `real module wiring boots against a live database and opensearch`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "search.database.url" to postgres.jdbcUrl,
                "search.database.username" to postgres.username,
                "search.database.password" to postgres.password,
                "search.opensearch.url" to "http://${opensearch.host}:${opensearch.getMappedPort(9200)}",
                "search.opensearch.alias" to "products-test",
                "search.opensearch.requestTimeoutMillis" to "5000",
                "search.catalogBaseUrl" to "http://localhost:0",
                "search.jwt.issuer" to "issuer",
                "search.jwt.audience" to "audience",
                "search.jwt.keys" to "kid-1=secret",
                "search.kafka.bootstrapServers" to "localhost:0",
                "search.kafka.topics" to "catalog.events,pricing.events",
                "search.kafka.groupId" to "search-consumer-test",
            )
        }
        application { module() }

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("UP"))
    }
}
