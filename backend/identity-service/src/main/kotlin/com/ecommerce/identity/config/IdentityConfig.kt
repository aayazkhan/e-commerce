package com.ecommerce.identity.config

import com.ecommerce.platform.database.DatabaseConfig
import io.ktor.server.config.ApplicationConfig

data class IdentityConfig(
    val database: DatabaseConfig,
    val redisUrl: String,
    val jwt: JwtConfig,
    val security: SecurityConfig,
    val challengeDelivery: ChallengeDeliveryConfig,
    val oauth: OAuthConfig,
    val kafka: KafkaConfig,
    val internalServiceToken: String,
) {
    companion object {
        fun from(config: ApplicationConfig): IdentityConfig = IdentityConfig(
            database = DatabaseConfig(
                jdbcUrl = config.required("identity.database.url"),
                username = config.required("identity.database.username"),
                password = config.required("identity.database.password"),
                maximumPoolSize = config.propertyOrNull("identity.database.maximumPoolSize")?.getString()?.toInt() ?: 15,
                connectionTimeoutMillis = config.propertyOrNull("identity.database.connectionTimeoutMillis")?.getString()?.toLong() ?: 2_000,
            ),
            redisUrl = config.required("identity.redis.url"),
            jwt = JwtConfig(
                issuer = config.required("identity.jwt.issuer"),
                audience = config.required("identity.jwt.audience"),
                activeKeyId = config.required("identity.jwt.activeKeyId"),
                keys = parseKeys(config.required("identity.jwt.keys")),
                accessTokenSeconds = config.propertyOrNull("identity.jwt.accessTokenSeconds")?.getString()?.toLong() ?: 900,
                refreshTokenDays = config.propertyOrNull("identity.jwt.refreshTokenDays")?.getString()?.toLong() ?: 30,
            ),
            security = SecurityConfig(
                maxLoginAttempts = config.propertyOrNull("identity.security.maxLoginAttempts")?.getString()?.toInt() ?: 8,
                lockMinutes = config.propertyOrNull("identity.security.lockMinutes")?.getString()?.toLong() ?: 15,
                otpExpirySeconds = config.propertyOrNull("identity.security.otpExpirySeconds")?.getString()?.toLong() ?: 300,
                otpMaxAttempts = config.propertyOrNull("identity.security.otpMaxAttempts")?.getString()?.toInt() ?: 5,
                otpResendSeconds = config.propertyOrNull("identity.security.otpResendSeconds")?.getString()?.toLong() ?: 60,
            ),
            challengeDelivery = ChallengeDeliveryConfig(
                url = config.propertyOrNull("identity.challengeDelivery.url")?.getString(),
                bearerToken = config.propertyOrNull("identity.challengeDelivery.bearerToken")?.getString(),
                resendApiKey = config.propertyOrNull("identity.challengeDelivery.resendApiKey")?.getString(),
                resendFromAddress = config.propertyOrNull("identity.challengeDelivery.resendFromAddress")?.getString()?.takeIf { it.isNotBlank() } ?: "onboarding@resend.dev",
            ),
            oauth = OAuthConfig(
                googleUserInfoUrl = config.propertyOrNull("identity.oauth.googleUserInfoUrl")?.getString(),
                appleUserInfoUrl = config.propertyOrNull("identity.oauth.appleUserInfoUrl")?.getString(),
            ),
            kafka = KafkaConfig(
                bootstrapServers = config.required("identity.kafka.bootstrapServers"),
                topic = config.propertyOrNull("identity.kafka.topic")?.getString() ?: "identity.events.v1",
                tenantId = config.required("identity.kafka.tenantId"),
            ),
            internalServiceToken = config.required("identity.internalServiceToken"),
        )

        private fun parseKeys(raw: String): Map<String, String> = raw.split(',')
            .map { entry ->
                val parts = entry.split('=', limit = 2)
                require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    "JWT_KEYS must contain comma-separated keyId=secret entries"
                }
                parts[0].trim() to parts[1].trim()
            }
            .toMap()
            .also { require(it.isNotEmpty()) { "At least one JWT signing key is required" } }
    }
}

data class JwtConfig(
    val issuer: String,
    val audience: String,
    val activeKeyId: String,
    val keys: Map<String, String>,
    val accessTokenSeconds: Long,
    val refreshTokenDays: Long,
) {
    init {
        require(activeKeyId in keys) { "Active JWT key must exist in JWT_KEYS" }
        require(accessTokenSeconds in 60..3600) { "Access token lifetime must be 60..3600 seconds" }
        require(refreshTokenDays in 1..365) { "Refresh token lifetime must be 1..365 days" }
    }
}

data class SecurityConfig(
    val maxLoginAttempts: Int,
    val lockMinutes: Long,
    val otpExpirySeconds: Long,
    val otpMaxAttempts: Int,
    val otpResendSeconds: Long,
)

data class ChallengeDeliveryConfig(
    /** Fallback webhook (e.g. a local stub) used for SMS/phone OTPs -- Resend is email-only. */
    val url: String?,
    val bearerToken: String?,
    val resendApiKey: String? = null,
    val resendFromAddress: String = "onboarding@resend.dev",
)

data class OAuthConfig(
    val googleUserInfoUrl: String?,
    val appleUserInfoUrl: String?,
)

data class KafkaConfig(
    val bootstrapServers: String,
    val topic: String,
    val tenantId: String,
)

private fun ApplicationConfig.required(path: String): String =
    propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }
        ?: error("Missing required configuration: $path")
