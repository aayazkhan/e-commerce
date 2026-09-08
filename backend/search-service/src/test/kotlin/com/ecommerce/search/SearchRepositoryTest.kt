package com.ecommerce.search

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRepositoryTest {
    @Test
    fun `processed reports whether an inbox event was already recorded`() {
        val database = FakeSearchDatabase()
        val repository = SearchRepository(database.dataSource())

        assertFalse(repository.processed("evt-1"))
        repository.record("evt-1", "ProductCreated", "product-1")
        assertTrue(repository.processed("evt-1"))
        assertFalse(repository.processed("evt-2"))
    }

    @Test
    fun `record is idempotent for a duplicate event id`() {
        val database = FakeSearchDatabase()
        val repository = SearchRepository(database.dataSource())

        repository.record("evt-1", "ProductCreated", "product-1")
        repository.record("evt-1", "ProductCreated", "product-1")

        assertEquals(1, database.recordedEventIds.size)
    }

    private class FakeSearchDatabase {
        val recordedEventIds = mutableSetOf<String>()

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "prepareStatement" -> statement(args!![0] as String)
                        "close" -> null
                        else -> defaultValue(method.returnType)
                    }
                },
            ) as Connection
            return Proxy.newProxyInstance(
                DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java),
                InvocationHandler { _, method, _ -> if (method.name == "getConnection") connection else defaultValue(method.returnType) },
            ) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            var eventId: String? = null
            return Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "setString" -> { if (args!![0] == 1) eventId = args[1] as String; null }
                        "setTimestamp" -> null
                        "executeQuery" -> resultSet(sql.startsWith("SELECT") && eventId in recordedEventIds)
                        "executeUpdate" -> { eventId?.let { recordedEventIds += it }; 1 }
                        "close" -> null
                        else -> defaultValue(method.returnType)
                    }
                },
            ) as PreparedStatement
        }

        private fun resultSet(hasRow: Boolean): ResultSet = Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "next" -> hasRow
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            },
        ) as ResultSet

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }
}
