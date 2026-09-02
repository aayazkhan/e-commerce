package com.ecommerce.identity.infrastructure

import com.ecommerce.platform.database.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

class DatabaseFactory private constructor(
    val dataSource: DataSource,
    private val closeAction: () -> Unit,
    migrate: () -> Unit,
) : AutoCloseable {
    constructor(config: DatabaseConfig) : this(createDataSource(config), "classpath:db/migration")

    /** Narrow DataSource seam for deterministic ping/close boundary tests. */
    internal constructor(dataSource: DataSource, closeAction: () -> Unit = {}) : this(dataSource, closeAction, {})

    init {
        migrate()
    }

    private constructor(dataSource: HikariDataSource, migrationsLocation: String) : this(
        dataSource,
        dataSource::close,
        {
            Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationsLocation)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        },
    )

    fun ping(): Boolean = dataSource.connection.use { !it.isClosed }

    override fun close() = closeAction()

    private companion object {
        fun createDataSource(config: DatabaseConfig): HikariDataSource {
            val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            connectionTimeout = config.connectionTimeoutMillis
            validationTimeout = config.connectionTimeoutMillis
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "identity-postgres"
            leakDetectionThreshold = 2_000
            }
            return HikariDataSource(hikari)
        }
    }
}
