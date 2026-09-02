package com.ecommerce.identity.infrastructure

import com.ecommerce.platform.database.DatabaseConfig
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_IDENTITY_INTEGRATION_TESTS", matches = "true")
class IdentityMigrationIntegrationTest {
    private val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

    @BeforeAll
    fun start() {
        postgres.start()
    }

    @Test
    fun `identity migrations apply to PostgreSQL`() {
        DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
            ),
        ).use { database ->
            assertTrue(database.ping())
        }
    }
}
