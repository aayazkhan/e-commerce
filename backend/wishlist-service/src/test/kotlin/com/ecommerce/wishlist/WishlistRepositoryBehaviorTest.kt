package com.ecommerce.wishlist

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WishlistRepositoryBehaviorTest {
    private val first = WishlistRecord("wish-1", "user-1", "product-1", "variant-1", "2026-08-20T00:00:00Z")
    private val second = first.copy(id = "wish-2", variantId = "variant-2")
    private val third = first.copy(id = "wish-3", variantId = "variant-3")

    @Test
    fun `add remove clear and outbox operations preserve idempotent side effects`() {
        val database = FakeWishlistDatabase()
        val repository = WishlistRepository(database.dataSource())

        assertEquals(first, repository.add("user-1", "product-1", "variant-1", "corr-add"))
        assertEquals(first, repository.add("user-1", "product-1", "variant-1", "corr-repeat"))
        assertTrue(repository.remove("user-1", "variant-1", "corr-remove"))
        assertFalse(repository.remove("user-1", "variant-1", "corr-repeat-remove"))
        assertEquals(2, repository.clear("user-1", "corr-clear"))
        assertEquals(0, repository.clear("user-1", "corr-empty"))

        val events = repository.unpublished(10)
        assertEquals(3, events.size)
        assertEquals(listOf("WishlistItemAdded", "WishlistItemRemoved", "WishlistCleared"), events.map { it.eventType })
        repository.markPublished(events.map { it.id }, Instant.parse("2026-08-20T00:00:00Z"))
        repository.markPublished(emptyList(), Instant.now())
        assertTrue(database.published)
    }

    @Test
    fun `list supports cursor pagination and rejects malformed cursors`() {
        val repository = WishlistRepository(FakeWishlistDatabase().dataSource())
        val page = repository.list("user-1", null, 2)
        assertEquals(listOf(first, second), page.records)
        assertTrue(page.hasMore)
        assertEquals(second.id, page.nextCursor?.let { decode(it).second })

        val cursorPage = repository.list("user-1", page.nextCursor, 50)
        assertEquals(3, cursorPage.records.size)
        assertEquals(null, cursorPage.nextCursor)

        val error = assertFailsWith<ApiException> { repository.list("user-1", "not-a-cursor", 10) }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun decode(value: String): Pair<Instant, String> {
        val text = String(java.util.Base64.getUrlDecoder().decode(value))
        val parts = text.split('|', limit = 2)
        return Instant.parse(parts[0]) to parts[1]
    }

    private class FakeWishlistDatabase {
        private val records = listOf(
            WishlistRecord("wish-1", "user-1", "product-1", "variant-1", "2026-08-20T00:00:00Z"),
            WishlistRecord("wish-2", "user-1", "product-1", "variant-2", "2026-08-20T00:00:00Z"),
            WishlistRecord("wish-3", "user-1", "product-1", "variant-3", "2026-08-20T00:00:00Z"),
        )
        var itemInserted = false
        var removed = true
        var clearRemaining = 2
        var published = false
        val events = mutableListOf<String>()

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "setAutoCommit", "commit", "rollback", "close" -> null
                    "createArrayOf" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val parameters = mutableMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setTimestamp", "setArray" -> parameters[args!![0] as Int] = args[1]
                    "executeQuery" -> resultSet(query(sql))
                    "executeUpdate" -> update(sql, parameters)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun update(sql: String, p: Map<Int, Any?>): Int {
            return when {
                sql.startsWith("INSERT INTO wishlist_items") -> if (!itemInserted) { itemInserted = true; events += "WishlistItemAdded"; 1 } else 0
                sql.startsWith("DELETE FROM wishlist_items WHERE user_id=? AND") -> if (removed) { removed = false; events += "WishlistItemRemoved"; 1 } else 0
                sql.startsWith("DELETE FROM wishlist_items WHERE user_id=?") -> if (clearRemaining > 0) { clearRemaining = 0; events += "WishlistCleared"; 2 } else 0
                sql.startsWith("INSERT INTO wishlist_outbox_events") -> 1
                sql.startsWith("UPDATE wishlist_outbox_events") -> { published = true; 1 }
                else -> 1
            }
        }

        private fun query(sql: String): List<Map<Any, Any?>> = when {
            sql.contains("FROM wishlist_items") -> records.map(::row)
            sql.contains("FROM wishlist_outbox_events") -> events.mapIndexed { index, event -> mapOf<Any, Any?>(1 to "event-${index + 1}", 2 to "user-1", 3 to event, 4 to 1, 5 to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")), 6 to "corr", 7 to "{}") }
            else -> emptyList()
        }

        private fun row(record: WishlistRecord) = mapOf<Any, Any?>("id" to record.id, "user_id" to record.userId, "product_id" to record.productId, "variant_id" to record.variantId, "created_at" to Timestamp.from(Instant.parse(record.createdAt)))

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key)
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getTimestamp" -> value(key) as? Timestamp
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
