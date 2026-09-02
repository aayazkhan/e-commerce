package com.ecommerce.platform.database

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 10,
    val connectionTimeoutMillis: Long = 2_000,
    val statementTimeoutMillis: Long = 2_000,
) {
    init {
        require(jdbcUrl.startsWith("jdbc:")) { "Database URL must be a JDBC URL" }
        require(username.isNotBlank()) { "Database username must not be blank" }
        require(maximumPoolSize in 1..100) { "Database pool size must be between 1 and 100" }
        require(connectionTimeoutMillis > 0) { "Connection timeout must be positive" }
        require(statementTimeoutMillis > 0) { "Statement timeout must be positive" }
    }
}
