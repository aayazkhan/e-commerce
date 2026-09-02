package com.ecommerce.identity.infrastructure

import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseFactoryBoundaryTest {
    @Test
    fun `ping reports both connection states and close delegates to the injected boundary`() {
        var closeCalls = 0
        val open = DatabaseFactory(dataSource(connectionClosed = false)) { closeCalls++ }
        assertTrue(open.ping())
        open.close()
        assertEquals(1, closeCalls)

        val closed = DatabaseFactory(dataSource(connectionClosed = true))
        assertFalse(closed.ping())
        closed.close()
    }

    private fun dataSource(connectionClosed: Boolean): DataSource {
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isClosed" -> connectionClosed
                "close" -> Unit
                else -> defaultValue(method.returnType)
            }
        } as Connection
        return Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getConnection" -> connection
                else -> defaultValue(method.returnType)
            }
        } as DataSource
    }

    private fun defaultValue(type: Class<*>): Any? = when {
        !type.isPrimitive -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
