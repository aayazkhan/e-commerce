package com.ecommerce.recommendation

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecommendationRepositoryBehaviorTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `accept records product and all pair combinations only once`() {
        val database = FakeRecommendationDatabase()
        val repository = RecommendationRepository(database.dataSource(), json)

        repository.accept(event(payload = """{"userId":"user-1","productId":"p-1","items":[{"productId":"p-1"},{"productId":"p-2"},{"productId":"p-3"}]}"""))
        repository.accept(event(payload = """{"user_id":"user-2","product_id":"p-2","items":[]}""", id = "event-2"))
        repository.accept(event(payload = """{"userId":"user-1","productId":"p-1"}"""))

        assertEquals(2, database.inboxInsertions)
        assertEquals(2, database.behaviorInsertions)
        assertEquals(2, database.popularUpserts)
        assertEquals(6, database.pairUpserts)
        assertEquals(3, database.commitCount)
        assertEquals(0, database.rollbackCount)
    }

    @Test
    fun `accept ignores malformed payloads and payloads without product or array items`() {
        val database = FakeRecommendationDatabase()
        val repository = RecommendationRepository(database.dataSource(), json)

        repository.accept(event(payload = "not-json"))
        repository.accept(event(payload = """{"userId":"user-1","items":{"productId":"p-1"}}""", id = "event-2"))
        repository.accept(event(payload = """{"userId":"user-1","items":[1,{"name":"missing-id"}]}""", id = "event-3"))
        repository.accept(event(payload = """{"productId":"p-4"}""", id = "event-4", occurredAt = "not-an-instant"))

        assertEquals(4, database.inboxInsertions)
        assertEquals(3, database.behaviorInsertions)
        assertEquals(1, database.popularUpserts)
        assertEquals(0, database.pairUpserts)
        assertEquals(4, database.commitCount)
    }

    @Test
    fun `record optionally updates popular products and preserves query context`() {
        val database = FakeRecommendationDatabase()
        val repository = RecommendationRepository(database.dataSource(), json)

        repository.record(BehaviorRequest("SEARCH", userId = "user-1", sessionId = "session-1", query = "boots"))
        repository.record(BehaviorRequest("PRODUCT_VIEWED", productId = "p-9"))

        assertEquals(2, database.behaviorInsertions)
        assertEquals(1, database.popularUpserts)
        assertEquals(2, database.commitCount)
        assertTrue(database.behaviorQueries.contains("boots"))
    }

    @Test
    fun `recommendations covers null inputs, clamped limits, popular fallback and personalization`() {
        val database = FakeRecommendationDatabase()
        val repository = RecommendationRepository(database.dataSource(), json)

        assertEquals(emptyList(), repository.recommendations(RecommendationType.RECENTLY_VIEWED, null, null, 0))
        assertEquals(listOf("p-1", "p-2"), repository.recommendations(RecommendationType.RECENTLY_VIEWED, "user-1", null, 2))
        assertEquals(emptyList(), repository.recommendations(RecommendationType.FREQUENTLY_BOUGHT_TOGETHER, "user-1", null, 999))
        assertEquals(listOf("p-1", "p-2"), repository.recommendations(RecommendationType.FREQUENTLY_BOUGHT_TOGETHER, "user-1", "p-1", 2))
        assertEquals(listOf("p-1", "p-2"), repository.recommendations(RecommendationType.SIMILAR, "user-1", null, 2))
        assertEquals(listOf("p-1", "p-2"), repository.recommendations(RecommendationType.SIMILAR, "user-1", "p-1", 2))
        assertEquals(listOf("p-1", "p-2"), repository.recommendations(RecommendationType.TRENDING, null, null, 2))
        assertEquals(listOf("p-1", "p-2"), repository.recommendations(RecommendationType.PERSONALIZED, "user-1", null, 2))
        assertEquals(listOf("p-1", "p-2"), repository.recommendations(RecommendationType.PERSONALIZED, null, null, 2))

        assertEquals(8, database.queryCount)
        assertTrue(database.observedLimits.all { it in 1..50 })
    }

    @Test
    fun `popular and DLQ support nullable errors and bounded messages`() {
        val database = FakeRecommendationDatabase()
        val repository = RecommendationRepository(database.dataSource(), json)

        assertEquals(listOf("p-1", "p-2"), repository.popular(2))
        repository.dlq(null, IllegalStateException())
        repository.dlq(event(), IllegalStateException("x".repeat(3_000)))

        assertEquals(2, database.dlqInsertions)
        assertEquals(listOf(null, "event-1"), database.dlqEventIds)
        assertEquals(2_000, database.lastDlqParameters[2].toString().length)
    }

    @Test
    fun `database failure rolls back accepting an event`() {
        val database = FakeRecommendationDatabase().also { it.failOnBehaviorInsert = SQLException("database unavailable") }
        val error = assertFailsWith<SQLException> {
            RecommendationRepository(database.dataSource(), json).accept(event(payload = """{"productId":"p-1"}"""))
        }

        assertEquals("database unavailable", error.message)
        assertEquals(1, database.rollbackCount)
        assertEquals(0, database.commitCount)
    }

    private fun event(id: String = "event-1", payload: String = "{}", occurredAt: String = "2026-08-20T00:00:00Z") = EventEnvelope(
        eventId = id, eventType = "ProductViewed", schemaVersion = 1, occurredAt = occurredAt,
        producer = "catalog", tenantId = "tenant-1", aggregateType = "Product", aggregateId = "p-1", correlationId = "corr-1", payloadJson = payload,
    )

    private class FakeRecommendationDatabase {
        val insertedEventIds = mutableSetOf<String>()
        var inboxInsertions = 0
        var behaviorInsertions = 0
        var popularUpserts = 0
        var pairUpserts = 0
        var dlqInsertions = 0
        var commitCount = 0
        var rollbackCount = 0
        var queryCount = 0
        val observedLimits = mutableListOf<Int>()
        val lastBehaviorParameters = mutableMapOf<Int, Any?>()
        val behaviorQueries = mutableListOf<String?>()
        val lastDlqParameters = mutableMapOf<Int, Any?>()
        val dlqEventIds = mutableListOf<String?>()
        var failOnBehaviorInsert: SQLException? = null

        fun dataSource(): DataSource = proxy(DataSource::class.java) { _, method, _ ->
            if (method.name == "getConnection") connection() else defaultValue(method.returnType)
        }

        private fun connection(): Connection = proxy(Connection::class.java) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                "setAutoCommit", "close" -> null
                "commit" -> { commitCount++; null }
                "rollback" -> { rollbackCount++; null }
                else -> defaultValue(method.returnType)
            }
        }

        private fun statement(sql: String): PreparedStatement {
            val parameters = mutableMapOf<Int, Any?>()
            return proxy(PreparedStatement::class.java) { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setDouble", "setObject" -> parameters[args!![0] as Int] = args[1]
                    "executeUpdate" -> update(sql, parameters)
                    "executeQuery" -> query(sql, parameters)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun update(sql: String, parameters: Map<Int, Any?>): Int {
            return when {
                sql.startsWith("INSERT INTO recommendation_inbox_events") -> {
                    val id = parameters[1].toString()
                    if (insertedEventIds.add(id)) { inboxInsertions++; 1 } else 0
                }
                sql.startsWith("INSERT INTO recommendation_behavior_events") -> {
                    failOnBehaviorInsert?.let { throw it }
                    behaviorInsertions++
                    lastBehaviorParameters.clear()
                    lastBehaviorParameters.putAll(parameters)
                    behaviorQueries += parameters[6]?.toString()
                    1
                }
                sql.startsWith("INSERT INTO recommendation_popular_products") -> { popularUpserts++; 1 }
                sql.startsWith("INSERT INTO recommendation_product_pairs") -> { pairUpserts++; 1 }
                sql.startsWith("INSERT INTO recommendation_dlq") -> { dlqInsertions++; dlqEventIds += parameters[1]?.toString(); lastDlqParameters.clear(); lastDlqParameters.putAll(parameters); 1 }
                else -> 1
            }
        }

        private fun query(sql: String, parameters: Map<Int, Any?>): ResultSet {
            queryCount++
            parameters[2]?.toString()?.toIntOrNull()?.let(observedLimits::add)
            val column = if (sql.contains("product_b")) "product_b" else "product_id"
            return resultSet(listOf(mapOf(column to "p-1"), mapOf(column to "p-2")))
        }

        private fun resultSet(rows: List<Map<String, String>>): ResultSet {
            var index = -1
            return proxy(ResultSet::class.java) { _, method, args ->
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> rows.getOrNull(index)?.let { row -> row[args?.firstOrNull()?.toString()] ?: row.values.firstOrNull() }
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
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
