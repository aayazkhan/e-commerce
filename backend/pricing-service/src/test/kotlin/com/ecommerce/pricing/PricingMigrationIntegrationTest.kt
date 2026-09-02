package com.ecommerce.pricing

import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_PHASE3_INTEGRATION_TESTS", matches = "true")
class PricingMigrationIntegrationTest {
    private val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    @BeforeAll fun start() { postgres.start() }
    @Test fun `pricing migration applies`() { ServiceDatabase(ServiceDatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), "classpath:db/migration").use { assertTrue(it.ping()) } }
}
