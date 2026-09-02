package com.ecommerce.platform.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseConfigTest {
    @Test
    fun `valid configuration preserves operational limits`() {
        val config = DatabaseConfig("jdbc:postgresql://localhost/commerce", "commerce", "secret", 25, 1_500, 3_000)

        assertEquals(25, config.maximumPoolSize)
        assertEquals(1_500, config.connectionTimeoutMillis)
        assertEquals(3_000, config.statementTimeoutMillis)

        val defaults = DatabaseConfig("jdbc:postgresql://localhost/commerce", "commerce", "secret")
        assertEquals(10, defaults.maximumPoolSize)
        assertEquals(2_000, defaults.connectionTimeoutMillis)
        assertEquals(2_000, defaults.statementTimeoutMillis)
    }

    @Test
    fun `configuration rejects invalid connection settings`() {
        assertFailsWith<IllegalArgumentException> { DatabaseConfig("postgres://localhost/commerce", "commerce", "secret") }
        assertFailsWith<IllegalArgumentException> { DatabaseConfig("jdbc:postgresql://localhost/commerce", "", "secret") }
        assertFailsWith<IllegalArgumentException> { DatabaseConfig("jdbc:postgresql://localhost/commerce", "commerce", "secret", 0) }
        assertFailsWith<IllegalArgumentException> { DatabaseConfig("jdbc:postgresql://localhost/commerce", "commerce", "secret", 101) }
        assertFailsWith<IllegalArgumentException> { DatabaseConfig("jdbc:postgresql://localhost/commerce", "commerce", "secret", connectionTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { DatabaseConfig("jdbc:postgresql://localhost/commerce", "commerce", "secret", statementTimeoutMillis = 0) }
    }
}
