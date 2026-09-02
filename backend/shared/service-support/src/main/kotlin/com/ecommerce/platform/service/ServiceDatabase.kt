package com.ecommerce.platform.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

data class ServiceDatabaseConfig(
    val url: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 15,
    val connectionTimeoutMillis: Long = 2_000,
    val minimumIdle: Int = 2,
    val idleTimeoutMillis: Long = 600_000,
    val maxLifetimeMillis: Long = 1_800_000,
)

class ServiceDatabase internal constructor(
    private val dataSource: DataSource,
    private val closeAction: () -> Unit,
    migrate: () -> Unit,
) : AutoCloseable {
    constructor(config: ServiceDatabaseConfig, migrationsLocation: String) : this(createDataSource(config, migrationsLocation), migrationsLocation)

    private constructor(dataSource: HikariDataSource, migrationsLocation: String) : this(
        dataSource,
        dataSource::close,
        {
            Flyway.configure().dataSource(dataSource).locations(migrationsLocation).baselineOnMigrate(true).load().migrate()
        },
    )

    init {
        migrate()
    }

    fun dataSource(): DataSource = dataSource

    fun ping(): Boolean = dataSource.connection.use { it.prepareStatement("SELECT 1").use { statement -> statement.execute() } }

    override fun close() = closeAction()

    private companion object {
        fun createDataSource(config: ServiceDatabaseConfig, migrationsLocation: String): HikariDataSource = HikariDataSource(HikariConfig().apply {
            require(config.maximumPoolSize > 0) { "maximumPoolSize must be positive" }
            require(config.minimumIdle in 0..config.maximumPoolSize) { "minimumIdle must be between 0 and maximumPoolSize" }
            require(config.connectionTimeoutMillis >= 250) { "connectionTimeoutMillis must be at least 250ms" }
            jdbcUrl = config.url
            username = config.username
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            minimumIdle = config.minimumIdle
            connectionTimeout = config.connectionTimeoutMillis
            idleTimeout = config.idleTimeoutMillis
            maxLifetime = config.maxLifetimeMillis
            poolName = "${migrationsLocation.substringAfterLast(':')}-pool"
            validate()
        })
    }
}
