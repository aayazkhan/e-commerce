package com.ecommerce.analytics

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsRepositoryBehaviorTest {
    private val database = AnalyticsDatabase()
    private val repository = AnalyticsRepository(database.dataSource(), Json.Default)

    @Test
    fun `accept filters PII aggregates exact metrics and ignores duplicate events`() {
        repository.accept(event("event-view", "ProductViewed", "{\"userId\":\"user-123\",\"items\":[{\"productId\":\"product-1\",\"sellerId\":\"seller-1\",\"email\":\"secret@example.com\"}]}"))
        repository.accept(event("event-order", "OrderCreated", "{\"totalMinor\":1000}"))
        repository.accept(event("event-paid", "PaymentCaptured", "{\"amountMinor\":1500}"))
        repository.accept(event("event-paid", "PaymentCaptured", "{\"amountMinor\":9999}"))
        repository.accept(event("event-bad", "Unknown", "not-json"))

        val summary = repository.summary(null, null)
        assertEquals(4, summary.events)
        assertEquals(1, summary.views)
        assertEquals(0, summary.searches)
        assertEquals(0, summary.carts)
        assertEquals(1, summary.orders)
        assertEquals(1, summary.paidOrders)
        assertEquals(0, summary.checkouts)
        assertEquals(1500, summary.revenueMinor)
        assertEquals(1.0, summary.conversionRate)
        assertEquals(0.0, summary.cartAbandonmentRate)

        assertTrue(database.eventMetricJson.single { it.first == "event-view" }.second.contains("productId"))
        assertFalse(database.eventMetricJson.single { it.first == "event-view" }.second.contains("email"))
        assertTrue(database.userHashes.single { it.first == "event-view" }.second != "user-123")
    }

    @Test
    fun `summary supports date windows and replay is safe to run repeatedly`() {
        repository.accept(event("event-view", "ProductViewed", "{}"))
        repository.accept(event("event-order", "OrderCreated", "{\"totalMinor\":1000}"))
        repository.accept(event("event-paid", "PaymentCaptured", "{\"amountMinor\":500}"))
        repository.accept(event("event-bad", "Unknown", "not-json"))
        val from = repository.summary("2026-08-20", "2026-08-21")
        assertEquals(4, from.events)
        assertEquals(500, from.revenueMinor)

        val firstReplay = repository.replay(ReplayRequest(from = "2026-08-20T00:00:00Z", to = "2026-08-21T00:00:00Z"))
        val secondReplay = repository.replay(ReplayRequest())
        assertEquals(4, firstReplay.eventsSeen)
        assertEquals(4, secondReplay.eventsSeen)
        assertTrue(firstReplay.idempotent)
        assertEquals(database.daily.events, repository.summary(null, null).events)
    }

    @Test
    fun `malformed payloads are accepted safely and failures reach the DLQ`() {
        repository.accept(event("malformed", "SearchPerformed", "{"))
        repository.dlq(event("dlq-event", "OrderCreated", "{}"), IllegalStateException("provider failed"))
        repository.dlq(null, RuntimeException())
        assertEquals(2, database.dlqCount)
        assertTrue(database.eventMetricJson.single { it.first == "malformed" }.second == "{}")
    }

    @Test
    fun `analytics covers cart checkout and partial line-item branches`() {
        repository.accept(event("cart", "CartUpdated", "{\"items\":[{\"productId\":\"product-only\"},{\"sellerId\":\"seller-only\"},{}]}"))
        repository.accept(event("checkout", "CheckoutStarted", "{\"amountMinor\":\"not-a-number\",\"totalMinor\":\"also-not-a-number\",\"items\":{}}"))

        val summary = repository.summary(null, null)
        assertEquals(2, summary.events)
        assertEquals(1, summary.carts)
        assertEquals(1, summary.checkouts)
        assertEquals(0.0, summary.conversionRate)
        assertEquals(1.0, summary.cartAbandonmentRate)
    }

    @Test
    fun `analytics handles empty conversion denominators and date-only replay bounds`() {
        assertEquals(0.0, repository.summary(null, null).conversionRate)
        assertEquals(0.0, repository.summary(null, null).cartAbandonmentRate)
        repository.accept(event("offset", "ProductViewed", "{}", "2026-08-20T10:15:00+00:00"))

        val replay = repository.replay(ReplayRequest(from = "2026-08-20", to = "2026-08-21"))
        assertEquals(1, replay.eventsSeen)
        assertTrue(replay.idempotent)
    }

    private fun event(id: String, type: String, payload: String, occurredAt: String = "2026-08-20T10:15:00Z") = EventEnvelope(id, type, 1, occurredAt, "test-producer", "tenant-1", "Order", "aggregate-1", "corr-1", null, payload)

    private class AnalyticsDatabase {
        val inbox = mutableSetOf<String>()
        val applied = mutableSetOf<String>()
        val events = mutableListOf<StoredEvent>()
        val eventMetricJson = mutableListOf<Pair<String, String>>()
        val userHashes = mutableListOf<Pair<String, String>>()
        val daily = Totals()
        val hourly = Totals()
        var dlqCount = 0

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "setAutoCommit", "commit", "rollback", "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val params = linkedMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setTimestamp", "setObject" -> params[args!![0] as Int] = args[1]
                    "executeQuery" -> resultSet(query(sql, params))
                    "executeUpdate" -> update(sql, params)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun query(sql: String, params: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.startsWith("SELECT coalesce(sum") -> listOf(mapOf<Any, Any?>(1 to daily.events, 2 to daily.views, 3 to daily.searches, 4 to daily.carts, 5 to daily.orders, 6 to daily.paid, 7 to daily.checkouts, 8 to daily.revenue))
            sql.startsWith("SELECT event_id,event_type") -> events.map { mapOf<Any, Any?>(1 to it.id, 2 to it.type, 3 to Timestamp.from(Instant.parse(it.occurredAt)), 4 to it.producer, 5 to it.aggregateId, 6 to it.metrics) }
            else -> emptyList()
        }

        private fun update(sql: String, params: Map<Int, Any?>): Int {
            var result = 1
            when {
                sql.startsWith("INSERT INTO analytics_inbox_events") -> result = if (inbox.add(params[1].toString())) 1 else 0
                sql.startsWith("INSERT INTO analytics_events") -> { events += StoredEvent(params[1].toString(), params[2].toString(), (params[3] as Timestamp).toInstant().toString(), params[4].toString(), params[5].toString(), params[7].toString()); eventMetricJson += params[1].toString() to params[7].toString(); params[6]?.toString()?.let { userHashes += params[1].toString() to it } }
                sql.startsWith("INSERT INTO analytics_applied_events") -> result = if (applied.add(params[1].toString())) 1 else 0
                sql.startsWith("INSERT INTO analytics_hourly") -> hourly.add(params)
                sql.startsWith("INSERT INTO analytics_daily") -> daily.add(params)
                sql.startsWith("INSERT INTO analytics_dlq") -> dlqCount++
            }
            return result
        }

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                val value = rows.getOrNull(index)?.get(key)
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value?.toString()
                    "getLong" -> (value as? Number)?.toLong() ?: 0L
                    "getTimestamp" -> value as? Timestamp
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }

    private data class StoredEvent(val id: String, val type: String, val occurredAt: String, val producer: String, val aggregateId: String, val metrics: String)
    private class Totals(var events: Long = 0, var views: Long = 0, var searches: Long = 0, var carts: Long = 0, var orders: Long = 0, var paid: Long = 0, var checkouts: Long = 0, var revenue: Long = 0) {
        fun add(params: Map<Int, Any?>) { events++; views += (params[2] as Number).toLong(); searches += (params[3] as Number).toLong(); carts += (params[4] as Number).toLong(); orders += (params[5] as Number).toLong(); paid += (params[6] as Number).toLong(); checkouts += (params[7] as Number).toLong(); revenue += (params[8] as Number).toLong() }
    }
}
