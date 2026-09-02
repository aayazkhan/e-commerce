package com.ecommerce.inventory

import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryConcurrencyIntegrationTest {
    companion object { @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine") }

    @Test
    fun onlyAvailableStockCanBeReservedConcurrently() {
        val database = ServiceDatabase(ServiceDatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), "classpath:db/migration")
        val repository = InventoryRepository(database.dataSource())
        repository.createItem(InventoryItemInput("", "test", "Test", "product_1", "variant_1", 10, 1), "admin", "test")
        val warehouse = repository.findByVariant("variant_1").single().warehouseId
        val pool = Executors.newFixedThreadPool(20)
        try {
            val results = (1..100).map { index -> pool.submit(Callable { runCatching { repository.reserve(ReservationInput("reservation-$index", "user-$index", null, null, listOf(ReservationItem("variant_1", warehouse, 1)), 900), "test") }.isSuccess }) }
            assertEquals(10, results.count { it.get() })
            assertEquals(0, repository.findByVariant("variant_1").single().available)
        } finally {
            pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS); database.close()
        }
    }
}
