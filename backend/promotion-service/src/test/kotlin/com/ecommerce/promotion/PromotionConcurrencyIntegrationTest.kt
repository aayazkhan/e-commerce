package com.ecommerce.promotion

import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@Tag("integration")
@Testcontainers
class PromotionConcurrencyIntegrationTest {
    companion object { @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine") }

    @Test
    fun couponUsageLimitIsAtomicUnderConcurrentRedemption() {
        val database = ServiceDatabase(ServiceDatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), "classpath:db/migration")
        val repository = PromotionRepository(database.dataSource())
        val promotion = repository.create(PromotionRequest("Concurrent", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, Instant.now().minusSeconds(60).toString(), currency = "INR", percentageBps = 1_000, usageLimit = 10), "admin", "test")
        repository.createCoupon(CouponRequest(promotion.id, "TEN-USES", usageLimit = 10), "admin", "test")
        val pool = Executors.newFixedThreadPool(20)
        try {
            val results = (1..100).map { index -> pool.submit(Callable { runCatching { repository.apply("user-$index", PromotionCalculateRequest(lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 10_000)), couponCode = "TEN-USES"), "redemption-$index", null, "test") }.isSuccess }) }
            assertEquals(10, results.count { it.get() })
        } finally {
            pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS); database.close()
        }
    }
}
