package com.ecommerce.refund

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.InternalHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
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
import kotlin.test.assertTrue

class RefundRepositoryPersistenceTest {
    private val request = RefundRequest(
        orderId = "order-1", paymentId = "payment-1", amountMinor = 1_250, currency = "INR",
        type = RefundType.ITEM_LEVEL, reason = "Damaged item", items = listOf(RefundItem("variant-1", 1, 1_250)),
    )

    @Test
    fun `create persists refund item history and outbox and replays same idempotency request`() {
        val database = FakeRefundDatabase()
        val repository = repository(database)

        val first = repository.create("user-1", request, "key-1", "corr-1")
        val replay = repository.create("user-1", request, "key-1", "corr-2")

        assertEquals(first, replay)
        assertEquals("user-1", first.userId)
        assertEquals(RefundStatus.REQUESTED, first.status)
        assertEquals(request.items, first.items)
        assertEquals(1, database.refundInsertions)
        assertEquals(1, database.itemInsertions)
        assertEquals(1, database.historyInsertions)
        assertEquals(1, database.outboxInsertions)
        assertEquals(2, database.commitCount)

        val pending = repository.unpublished(10)
        assertEquals(1, pending.size)
        repository.markPublished(listOf(pending.single().id), Instant.parse("2026-08-21T00:00:00Z"))
        assertTrue(repository.unpublished(10).isEmpty())
        repository.markPublished(emptyList(), Instant.parse("2026-08-21T00:00:00Z"))
    }

    @Test
    fun `reusing an idempotency key with a different request is a conflict`() {
        val database = FakeRefundDatabase()
        val repository = repository(database)
        repository.create("user-1", request, "key-1", "corr-1")

        val error = assertFailsWith<ApiException> {
            repository.create("user-1", request.copy(amountMinor = 1_300), "key-1", "corr-2")
        }

        assertEquals(ErrorCode.CONFLICT, error.errorCode)
        assertEquals(1, database.refundInsertions)
        assertEquals(1, database.rollbackCount)
    }

    @Test
    fun `owned reads enforce user ownership and list returns persisted refund`() {
        val database = FakeRefundDatabase()
        val repository = repository(database)
        val created = repository.create("user-1", request, "key-1", "corr-1")

        assertEquals(created, repository.getOwned("user-1", created.id))
        assertNull(repository.getOwned("other-user", created.id))
        assertEquals(listOf(created), repository.listOwned("user-1"))

        database.orphanListEntry = true
        assertEquals(listOf(created), repository.listOwned("user-1"))
    }

    @Test
    fun `rejection moves requested refund to terminal state and rejects repeat approval`() {
        val database = FakeRefundDatabase()
        val repository = repository(database)
        val created = repository.create("user-1", request, "key-1", "corr-1")

        val rejected = repository.approve(created.id, RefundDecision(false, "outside policy"), "admin-1", "corr-2")

        assertEquals(RefundStatus.REJECTED, rejected.status)
        assertEquals(2, database.historyInsertions)
        val error = assertFailsWith<ApiException> {
            repository.approve(created.id, RefundDecision(false, "again"), "admin-1", "corr-3")
        }
        assertEquals(ErrorCode.CONFLICT, error.errorCode)
    }

    @Test
    fun `approval transitions requested refund and persists provider completion`() {
        val database = FakeRefundDatabase()
        val server = server(200, "{\"paymentId\":\"payment-1\",\"amountMinor\":1250,\"status\":\"COMPLETED\",\"providerRefundId\":\"provider-rfd-1\",\"createdAt\":\"2026-08-20T00:00:00Z\"}")
        try {
            val created = repository(database, "http://localhost:${server.address.port}").create("user-1", request, "key-1", "corr-1")
            val completed = repository(database, "http://localhost:${server.address.port}").approve(created.id, RefundDecision(true, "approved"), "admin-1", "corr-2")

            assertEquals(RefundStatus.COMPLETED, completed.status)
            assertEquals("provider-rfd-1", completed.providerRefundId)
            assertEquals(3, database.historyInsertions)
            assertEquals(2, database.outboxInsertions)
            assertEquals(RefundStatus.COMPLETED.name, database.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `approval preserves processing state when payment provider fails and can recover`() {
        val database = FakeRefundDatabase()
        val failedServer = server(503, "unavailable")
        try {
            val created = repository(database, "http://localhost:${failedServer.address.port}").create("user-1", request, "key-1", "corr-1")
            val error = assertFailsWith<ApiException> {
                repository(database, "http://localhost:${failedServer.address.port}").approve(created.id, RefundDecision(true), "admin-1", "corr-2")
            }
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode)
            assertEquals(RefundStatus.PROCESSING.name, database.status)

            val processingRejection = assertFailsWith<ApiException> {
                repository(database, "http://localhost:${failedServer.address.port}").approve(
                    database.savedId!!,
                    RefundDecision(false, "cannot reject while provider recovery is in progress"),
                    "admin-1",
                    "corr-reject-processing",
                )
            }
            assertEquals(ErrorCode.CONFLICT, processingRejection.errorCode)
        } finally {
            failedServer.stop(0)
        }

        val recoveryServer = server(200, "{\"paymentId\":\"payment-1\",\"amountMinor\":1250,\"status\":\"COMPLETED\",\"providerRefundId\":\"recovered-rfd\",\"createdAt\":\"2026-08-20T00:00:00Z\"}")
        try {
            val recovered = repository(database, "http://localhost:${recoveryServer.address.port}").approve("${database.savedId}", RefundDecision(true), "admin-1", "corr-3")
            assertEquals(RefundStatus.COMPLETED, recovered.status)
            assertEquals("recovered-rfd", recovered.providerRefundId)
        } finally {
            recoveryServer.stop(0)
        }
    }

    private fun repository(database: FakeRefundDatabase, paymentBaseUrl: String = "http://127.0.0.1:1") = RefundRepository(database.dataSource(), InternalHttpClient(paymentBaseUrl), emptyMap())

    private fun server(status: Int, body: String): HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).also { http ->
        http.createContext("/") { exchange: HttpExchange ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
    }

    private class FakeRefundDatabase {
        var savedId: String? = null
        var savedUser: String? = null
        var savedHash: String? = null
        var status = "REQUESTED"
        var refundInsertions = 0
        var itemInsertions = 0
        var historyInsertions = 0
        var outboxInsertions = 0
        var commitCount = 0
        var rollbackCount = 0
        var providerRefundId: String? = null
        var orphanListEntry = false
        var outboxPublished = false

        fun dataSource(): DataSource = proxy(DataSource::class.java) { _, method, _ ->
            if (method.name == "getConnection") connection() else defaultValue(method.returnType)
        }

        private fun connection(): Connection = proxy(Connection::class.java) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                "setAutoCommit", "close", "rollback" -> if (method.name == "rollback") { rollbackCount++; null } else null
                "commit" -> { commitCount++; null }
                "createArrayOf" -> null
                else -> defaultValue(method.returnType)
            }
        }

        private fun statement(sql: String): PreparedStatement {
            val parameters = mutableMapOf<Int, Any?>()
            return proxy(PreparedStatement::class.java) { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setTimestamp", "setObject", "setArray" -> parameters[args!![0] as Int] = args[1]
                    "setNull" -> parameters[args!![0] as Int] = null
                    "executeQuery" -> query(sql, parameters)
                    "executeUpdate" -> update(sql, parameters)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun update(sql: String, p: Map<Int, Any?>): Int = when {
            sql.startsWith("INSERT INTO refunds") -> {
                savedId = p[1]?.toString(); savedUser = p[2]?.toString(); savedHash = p[11]?.toString(); refundInsertions++; 1
            }
            sql.startsWith("INSERT INTO refund_items") -> { itemInsertions++; 1 }
            sql.startsWith("INSERT INTO refund_status_history") -> { historyInsertions++; 1 }
            sql.startsWith("UPDATE refunds SET status='REJECTED'") -> { status = "REJECTED"; 1 }
            sql.startsWith("UPDATE refunds SET status='PROCESSING'") -> { status = "PROCESSING"; 1 }
            sql.startsWith("UPDATE refunds SET status='COMPLETED'") -> { status = "COMPLETED"; providerRefundId = p[1]?.toString(); 1 }
            sql.startsWith("INSERT INTO refund_outbox_events") -> { outboxInsertions++; 1 }
            sql.startsWith("UPDATE refund_outbox_events") -> { outboxPublished = true; 1 }
            else -> 1
        }

        private fun query(sql: String, p: Map<Int, Any?>): ResultSet {
            val rows = when {
                sql.startsWith("SELECT request_hash,id FROM refunds") && p[1] == savedUser -> listOf(mapOf<Any, Any?>(1 to savedHash, 2 to savedId))
                sql.startsWith("SELECT request_hash,id FROM refunds") -> emptyList()
                sql.startsWith("SELECT id FROM refunds") && p[1] == savedUser -> buildList {
                    add(mapOf<Any, Any?>(1 to savedId))
                    if (orphanListEntry) add(mapOf<Any, Any?>(1 to "missing-refund"))
                }
                sql.startsWith("SELECT * FROM refunds") && p[1] == savedId && (p[2] == null || p[2] == savedUser) -> listOf(refundRow())
                sql.startsWith("SELECT variant_id,quantity,amount_minor") && p[1] == savedId -> listOf(mapOf<Any, Any?>(1 to "variant-1", 2 to 1, 3 to 1_250L))
                sql.startsWith("SELECT id,aggregate_id,event_type") && outboxInsertions > 0 && !outboxPublished -> listOf(mapOf<Any, Any?>(1 to "refund-event-1", 2 to savedId, 3 to "RefundRequested", 4 to 1, 5 to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")), 6 to "corr-1", 7 to "{}"))
                else -> emptyList()
            }
            return resultSet(rows)
        }

        private fun refundRow(): Map<Any, Any?> = mapOf(
            "id" to savedId, "user_id" to savedUser, "order_id" to "order-1", "payment_id" to "payment-1", "amount_minor" to 1_250L,
            "currency" to "INR", "type" to "ITEM_LEVEL", "status" to status, "reason" to "Damaged item", "provider_refund_id" to providerRefundId,
            "created_at" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")), "updated_at" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        )

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            var wasNull = false
            fun value(key: Any?): Any? = rows.getOrNull(index)?.let { row -> if (key == null) null else row[key] ?: row[key.toString()] }
            return proxy(ResultSet::class.java) { _, method, args ->
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(args?.firstOrNull()).also { wasNull = it == null }?.toString()
                    "getLong" -> value(args?.firstOrNull()).also { wasNull = it == null }?.let { (it as Number).toLong() } ?: 0L
                    "getInt" -> value(args?.firstOrNull()).also { wasNull = it == null }?.let { (it as Number).toInt() } ?: 0
                    "getTimestamp" -> value(args?.firstOrNull()).also { wasNull = it == null } as? Timestamp
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
