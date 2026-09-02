package com.ecommerce.platform.service

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceDatabaseBoundaryTest {
    @Test
    fun `ping executes the health query and close delegates to the boundary`() {
        val executedSql = mutableListOf<String>()
        var closeCalls = 0
        val database = ServiceDatabase(
            dataSource = dataSource(executeResult = true, executedSql),
            closeAction = { closeCalls++ },
            migrate = {},
        )

        assertTrue(database.ping())
        database.close()

        assertEquals(listOf("SELECT 1"), executedSql)
        assertEquals(1, closeCalls)
    }

    @Test
    fun `ping preserves a negative health-check result`() {
        val database = ServiceDatabase(
            dataSource = dataSource(executeResult = false, mutableListOf()),
            closeAction = {},
            migrate = {},
        )

        assertFalse(database.ping())
    }

    private fun dataSource(executeResult: Boolean, executedSql: MutableList<String>): DataSource {
        val statement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execute" -> executeResult
                "close" -> Unit
                else -> defaultValue(method.returnType, args)
            }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> {
                    executedSql += args?.firstOrNull() as String
                    statement
                }
                "close" -> Unit
                else -> defaultValue(method.returnType, args)
            }
        } as Connection
        return Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, args ->
            if (method.name == "getConnection") connection else defaultValue(method.returnType, args)
        } as DataSource
    }

    private fun defaultValue(type: Class<*>, args: Array<out Any?>?): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> if (type == String::class.java && args != null) args.firstOrNull()?.toString() else null
    }
}
