package com.ecommerce.audit

import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AuditRepositoryBehaviorTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val event = EventEnvelope("event-1", "OrderCreated", 1, "2026-08-20T00:00:00Z", "order", "tenant-1", "Order", "order-1", "corr-1", payloadJson = "{\"actorId\":\"user-1\",\"token\":\"secret\",\"items\":[{\"sku\":\"sku-1\",\"password\":\"hidden\"}]}")

    @Test
    fun `consume is idempotent and sanitizes sensitive event fields`() {
        val database = FakeAuditDatabase()
        val repository = AuditRepository(database.dataSource(), json)

        repository.consume(event)
        repository.consume(event)

        assertEquals(1, database.auditInsertions)
        assertEquals("user-1", database.lastAudit["actor_id"])
        assertTrue(database.lastAudit["after_json"].orEmpty().contains("sku"))
        assertTrue(!database.lastAudit["after_json"].orEmpty().contains("token"))
        assertTrue(!database.lastAudit["after_json"].orEmpty().contains("password"))

        repository.consume(event.copy(eventId = "event-2", payloadJson = "malformed"))
        assertEquals(2, database.auditInsertions)
        assertEquals(null, database.lastAudit["actor_id"])

        repository.consume(event.copy(eventId = "event-3", payloadJson = "{\"safe\":true}"))
        assertEquals(3, database.auditInsertions)
        assertEquals(null, database.lastAudit["actor_id"])
    }

    @Test
    fun `record masks input, hashes IP, truncates text and returns persisted response`() {
        val database = FakeAuditDatabase()
        val repository = AuditRepository(database.dataSource(), json)
        val response = repository.record(
            AuditRequest(
                actorId = "admin-1", actorType = "A".repeat(50), action = "ACTION".repeat(30),
                resourceType = "resource", resourceId = "resource-1", sellerId = "seller-1",
                beforeJson = "{\"secret\":\"do-not-store\",\"safe\":true}",
                afterJson = "{\"cardNumber\":\"4111111111111111\",\"safe\":\"yes\"}",
                reason = "reason", requestId = "request-1", traceId = "trace-1", ip = "127.0.0.1", userAgent = "agent",
            ),
        )
        assertEquals(database.lastAudit["id"], response.id)
        assertEquals(40, database.lastAudit["actor_type"]?.length)
        assertEquals(120, database.lastAudit["action"]?.length)
        assertTrue(database.lastAudit["before_json"].orEmpty().contains("safe"))
        assertTrue(!database.lastAudit["before_json"].orEmpty().contains("secret"))
        assertTrue(database.lastAudit["ip_hash"]?.length == 64)
        assertEquals(response, repository.get(response.id))
        assertEquals(null, repository.get("missing"))
    }

    @Test
    fun `search applies optional filters and returns cursor for an extra row`() {
        val repository = AuditRepository(FakeAuditDatabase().dataSource(), json)
        val page = repository.search(
            actor = "admin-1", action = "UPDATE", resourceType = "Order", resourceId = "order-1", sellerId = "seller-1", traceId = "trace-1",
            from = "2026-08-19T00:00:00Z", to = "2026-08-21T00:00:00Z", cursor = -4, limit = 2,
        )
        assertEquals(2, page.items.size)
        assertEquals("-2", page.nextCursor)
    }

    @Test
    fun `search without optional filters returns the final page and normalizes limit`() {
        val repository = AuditRepository(FakeAuditDatabase().dataSource(), json)

        val page = repository.search(
            actor = null,
            action = null,
            resourceType = null,
            resourceId = null,
            sellerId = null,
            traceId = null,
            from = null,
            to = null,
            cursor = 0,
            limit = 500,
        )

        assertEquals(3, page.items.size)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun `record replaces malformed before and after audit JSON with empty objects`() {
        val database = FakeAuditDatabase()
        val repository = AuditRepository(database.dataSource(), json)

        repository.record(
            AuditRequest(
                actorId = "user-1",
                action = "PROFILE_UPDATE",
                resourceType = "user",
                resourceId = "user-1",
                beforeJson = "{bad",
                afterJson = "[also-bad",
            ),
        )

        assertEquals("{}", database.lastAudit["before_json"])
        assertEquals("{}", database.lastAudit["after_json"])
    }

    @Test
    fun `DLQ records nullable events and transaction rolls back persistence failures`() {
        val database = FakeAuditDatabase()
        val repository = AuditRepository(database.dataSource(), json)
        repository.dlq(null, IllegalStateException("consumer failure"))
        assertEquals(1, database.dlqInsertions)
        repository.dlq(event, RuntimeException())
        assertEquals(2, database.dlqInsertions)

        val failing = FakeAuditDatabase().also { it.failAuditInsert = SQLException("database down", "08001") }
        val error = assertFailsWith<SQLException> {
            AuditRepository(failing.dataSource(), json).record(AuditRequest("user-1", action = "CREATE", resourceType = "Order", resourceId = "order-1"))
        }
        assertEquals("08001", error.sqlState)
        assertEquals(1, failing.rollbackCount)
    }

    private class FakeAuditDatabase {
        val inboxIds = mutableSetOf<String>()
        val lastAudit = linkedMapOf<String, String?>()
        var auditInsertions = 0
        var dlqInsertions = 0
        var rollbackCount = 0
        var failAuditInsert: SQLException? = null

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "setAutoCommit", "commit", "close" -> null
                    "rollback" -> { rollbackCount++; null }
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val p = mutableMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setObject", "setTimestamp" -> p[args!![0] as Int] = args[1]
                    "executeQuery" -> resultSet(query(sql, p))
                    "executeUpdate" -> update(sql, p)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun update(sql: String, p: Map<Int, Any?>): Int {
            when {
                sql.startsWith("INSERT INTO audit_inbox_events") -> return if (inboxIds.add(p[1].toString())) 1 else 0
                sql.startsWith("INSERT INTO audit_events") -> {
                    failAuditInsert?.let { throw it }
                    auditInsertions++
                    val keys = listOf("id", "actor_id", "actor_type", "action", "resource_type", "resource_id", "seller_id", "before_json", "after_json", "reason", "request_id", "trace_id", "ip_hash", "user_agent")
                    keys.forEachIndexed { index, key -> lastAudit[key] = p[index + 1]?.toString() }
                    lastAudit["created_at"] = "2026-08-20T00:00:00Z"
                }
                sql.startsWith("INSERT INTO audit_dlq") -> dlqInsertions++
            }
            return 1
        }

        private fun query(sql: String, p: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.contains("FROM audit_events") && sql.contains("WHERE id=?") -> if (p[1] == lastAudit["id"]) listOf(lastAuditRow()) else emptyList()
            sql.contains("FROM audit_events") -> listOf(searchRow("audit-1"), searchRow("audit-2"), searchRow("audit-3"))
            else -> emptyList()
        }

        private fun lastAuditRow(): Map<Any, Any?> = buildMap {
            lastAudit.forEach { (key, value) -> put(key, value) }
            put("created_at", Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")))
        }

        private fun searchRow(id: String): Map<Any, Any?> = mapOf<Any, Any?>(
            "id" to id, "actor_id" to "admin-1", "actor_type" to "ADMIN", "action" to "UPDATE", "resource_type" to "Order", "resource_id" to "order-1",
            "seller_id" to "seller-1", "before_json" to "{}", "after_json" to "{}", "reason" to "test", "request_id" to "request-1", "trace_id" to "trace-1",
            "created_at" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        )

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key)
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getTimestamp" -> value(key) as? Timestamp ?: Timestamp.from(Instant.parse("2026-08-20T00:00:00Z"))
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
}
