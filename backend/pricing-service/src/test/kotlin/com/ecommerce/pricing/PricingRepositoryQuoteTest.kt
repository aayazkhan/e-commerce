package com.ecommerce.pricing

import com.ecommerce.platform.error.ApiException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PricingRepositoryQuoteTest {
    @Test
    fun `quote computes sale price tax totals and version fingerprint`() {
        val repository = PricingRepository(FakePricingDatabase().dataSource())

        val quote = repository.quote(listOf(QuoteItem("product-1", "variant-1", 2)), "INR", "IN", "DEFAULT")

        assertEquals(1, quote.items.size)
        assertEquals(1_500L, quote.items.single().unitMinor)
        assertEquals(3_000L, quote.subtotalMinor)
        assertEquals(270L, quote.taxMinor)
        assertEquals(3_270L, quote.totalMinor)
        assertEquals("price-1:2", quote.priceVersion)
    }

    @Test
    fun `quote rejects empty and out of-range quantities and missing prices`() {
        val repository = PricingRepository(FakePricingDatabase().dataSource())

        assertEquals(400, assertFailsWith<ApiException> { repository.quote(emptyList(), "INR", "IN", "DEFAULT") }.statusCode)
        assertEquals(400, assertFailsWith<ApiException> { repository.quote(listOf(QuoteItem("p", null, 0)), "INR", "IN", "DEFAULT") }.statusCode)
        assertEquals(400, assertFailsWith<ApiException> { repository.quote(listOf(QuoteItem("p", null, 1_001)), "INR", "IN", "DEFAULT") }.statusCode)
        assertEquals(404, assertFailsWith<ApiException> { PricingRepository(FakePricingDatabase(withPrice = false).dataSource()).quote(listOf(QuoteItem("missing", null, 1)), "INR", "IN", "DEFAULT") }.statusCode)
    }

    @Test
    fun `current maps nullable sale and effective dates and returns null for an empty query`() {
        val nullable = PricingRepository(FakePricingDatabase(withSale = false, withEnd = false).dataSource())
        val price = nullable.current("product-1", null, "INR", "IN", "DEFAULT", Instant.parse("2026-08-21T00:00:00Z"))
        assertEquals(2_000L, price?.unitMinor)
        assertNull(price?.saleMinor)
        assertNull(price?.effectiveTo)

        val missing = PricingRepository(FakePricingDatabase(withPrice = false).dataSource())
        assertNull(missing.current("missing", null, "INR", "IN", "DEFAULT", Instant.parse("2026-08-21T00:00:00Z")))
    }

    @Test
    fun `create rejects every invalid financial input before persistence`() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val valid = PriceInput("product-1", null, null, "INR", "IN", "DEFAULT", 2_000, 1_500, 900, now, null)
        val invalidInputs = listOf(
            valid.copy(currency = "usd"),
            valid.copy(baseMinor = -1),
            valid.copy(saleMinor = -1),
            valid.copy(saleMinor = 2_001),
            valid.copy(taxRateBps = -1),
            valid.copy(taxRateBps = 10_001),
            valid.copy(effectiveTo = now.minusSeconds(1)),
        )

        invalidInputs.forEach { input ->
            val error = assertFailsWith<ApiException> { PricingRepository(FakePricingDatabase().dataSource()).create(input, "actor-1", "corr-1") }
            assertEquals(400, error.statusCode)
        }
    }

    @Test
    fun `create accepts nullable sale and open ended effective dates`() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val input = PriceInput("product-1", null, null, "INR", "IN", "DEFAULT", 2_000, null, 0, now, null)

        val created = PricingRepository(FakePricingDatabase(withSale = false, withEnd = false).dataSource())
            .create(input, "actor-1", "corr-1")

        assertEquals("product-1", created.productId)
        assertNull(created.saleMinor)
        assertNull(created.effectiveTo)
    }

    @Test
    fun `create accepts sale price and bounded effective dates`() {
        val start = Instant.parse("2026-08-20T00:00:00Z")
        val end = Instant.parse("2026-12-31T23:59:59Z")
        val input = PriceInput("product-1", "variant-1", "seller-1", "INR", "IN", "DEFAULT", 2_000, 1_500, 900, start, end)

        val created = PricingRepository(FakePricingDatabase().dataSource()).create(input, "actor-1", "corr-1")

        assertEquals("price-1", created.id)
        assertEquals(1_500L, created.saleMinor)
        assertEquals("2026-12-31T00:00:00Z", created.effectiveTo)
    }

    @Test
    fun `update returns the persisted version and missing prices are rejected`() {
        val now = Instant.parse("2026-08-20T00:00:00Z")
        val input = PriceInput("product-1", "variant-1", "seller-1", "INR", "IN", "DEFAULT", 2_000, 1_500, 900, now, now.plusSeconds(86_400))
        val repository = PricingRepository(FakePricingDatabase().dataSource())

        val updated = repository.update("price-1", input, "actor-1", "corr-1")

        assertEquals("price-1", updated.id)
        assertEquals(2L, updated.version)
        assertEquals(1_500L, updated.saleMinor)
        val updatedWithoutOptionalValues = repository.update("price-1", input.copy(saleMinor = null, effectiveTo = null), "actor-1", "corr-2")
        assertEquals("price-1", updatedWithoutOptionalValues.id)
        assertEquals(404, assertFailsWith<ApiException> {
            PricingRepository(FakePricingDatabase(withPrice = false).dataSource()).update("missing", input, "actor-1", "corr-1")
        }.statusCode)
        assertEquals(404, assertFailsWith<ApiException> {
            PricingRepository(FakePricingDatabase(withPrice = false).dataSource()).delete("missing", "actor-1", "corr-1")
        }.statusCode)
        val deleted = PricingRepository(FakePricingDatabase().dataSource()).delete("price-1", "actor-1", "corr-3")
        assertEquals(1, deleted)
    }

    @Test
    fun `outbox publication handles both empty and non-empty id batches`() {
        val database = FakePricingDatabase()
        val repository = PricingRepository(database.dataSource())
        val publishedAt = Instant.parse("2026-08-20T00:00:00Z")

        repository.markPublished(emptyList(), publishedAt)
        repository.markPublished(listOf("event-1"), publishedAt)

        assertEquals(2, database.executeUpdateCount)
    }

    @Test
    fun `unpublished returns mapped records and an empty list when none are pending`() {
        val pending = PricingRepository(FakePricingDatabase(withOutbox = true).dataSource()).unpublished(10)
        val empty = PricingRepository(FakePricingDatabase(withOutbox = false).dataSource()).unpublished(10)

        assertEquals("outbox-1", pending.single().id)
        assertEquals("Price", pending.single().aggregateType)
        assertEquals("PriceUpdated", pending.single().eventType)
        assertEquals(emptyList(), empty)
    }

    private class FakePricingDatabase(
        private val withPrice: Boolean = true,
        private val withSale: Boolean = true,
        private val withEnd: Boolean = true,
        private val withOutbox: Boolean = false,
    ) {
        var executeUpdateCount = 0

        fun dataSource(): DataSource = proxy(DataSource::class.java) { _, method, _ ->
            if (method.name == "getConnection") connection() else defaultValue(method.returnType)
        }

        private fun connection(): Connection = proxy(Connection::class.java) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                "setAutoCommit", "commit", "rollback", "close" -> null
                else -> defaultValue(method.returnType)
            }
        }

        private fun statement(sql: String): PreparedStatement = proxy(PreparedStatement::class.java) { _, method, _ ->
            when (method.name) {
                "executeQuery" -> resultSet(sql)
                "executeUpdate" -> 1.also { executeUpdateCount++ }
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }

        private fun resultSet(sql: String): ResultSet {
            val row = if (sql.startsWith("SELECT id,aggregate_type")) {
                if (withOutbox) mapOf<Any, Any?>(
                    1 to "outbox-1", 2 to "Price", 3 to "price-1", 4 to "PriceUpdated", 5 to 1,
                    6 to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")), 7 to "corr-1", 8 to "{}",
                ) else null
            } else if (withPrice) mapOf<String, Any?>(
                "id" to "price-1", "product_id" to "product-1", "variant_id" to "variant-1", "seller_id" to null,
                "currency" to "INR", "region" to "IN", "customer_segment" to "DEFAULT", "base_minor" to 2_000L,
                "sale_minor" to if (withSale) 1_500L else null, "tax_rate_bps" to 900, "effective_from" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
                "effective_to" to if (withEnd) Timestamp.from(Instant.parse("2026-12-31T00:00:00Z")) else null, "version" to 2L,
                "created_by" to "actor-1", "created_at" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")), "updated_at" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
            ) else null
            var beforeFirst = true
            var wasNull = false
            return proxy(ResultSet::class.java) { _, method, args ->
                when (method.name) {
                    "next" -> if (beforeFirst) { beforeFirst = false; row != null } else false
                    "getString", "getLong", "getInt", "getTimestamp" -> {
                        val value = row?.get(args?.firstOrNull())
                        wasNull = value == null
                        when (method.name) {
                            "getString" -> value?.toString()
                            "getLong" -> (value as? Number)?.toLong() ?: 0L
                            "getInt" -> (value as? Number)?.toInt() ?: 0
                            else -> value as? Timestamp
                        }
                    }
                    "wasNull" -> wasNull
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }
}
