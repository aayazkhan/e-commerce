package com.ecommerce.platform.service

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServiceDatabaseValidationTest {
    @Test
    fun `database rejects non-positive pool sizes`() {
        assertFailsWith<IllegalArgumentException> { ServiceDatabase(ServiceDatabaseConfig("jdbc:invalid", "user", "password", maximumPoolSize = 0), "classpath:db/migration") }
        assertFailsWith<IllegalArgumentException> { ServiceDatabase(ServiceDatabaseConfig("jdbc:invalid", "user", "password", maximumPoolSize = 2, minimumIdle = 3), "classpath:db/migration") }
        assertFailsWith<IllegalArgumentException> { ServiceDatabase(ServiceDatabaseConfig("jdbc:invalid", "user", "password", maximumPoolSize = 2, minimumIdle = -1), "classpath:db/migration") }
    }

    @Test
    fun `database rejects connection timeout below the supported floor`() {
        assertFailsWith<IllegalArgumentException> { ServiceDatabase(ServiceDatabaseConfig("jdbc:invalid", "user", "password", connectionTimeoutMillis = 249), "classpath:db/migration") }
    }
}
