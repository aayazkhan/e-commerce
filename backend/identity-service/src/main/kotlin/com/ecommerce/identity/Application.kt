package com.ecommerce.identity
import io.ktor.server.application.log

import com.ecommerce.identity.application.IdentityService
import com.ecommerce.identity.config.IdentityConfig
import com.ecommerce.identity.http.identityRoutes
import com.ecommerce.identity.infrastructure.DatabaseFactory
import com.ecommerce.identity.infrastructure.IdentityRepository
import com.ecommerce.identity.infrastructure.OutboxPublisher
import com.ecommerce.identity.security.HttpChallengeDelivery
import com.ecommerce.identity.security.MultiChannelChallengeDelivery
import com.ecommerce.identity.security.Msg91SmsProvider
import com.ecommerce.identity.security.ResendEmailProvider
import com.ecommerce.identity.security.JwtService
import com.ecommerce.identity.security.PasswordHasher
import com.ecommerce.identity.security.ConfiguredOAuthIdentityVerifier
import com.ecommerce.identity.security.RedisRateLimiter
import com.ecommerce.identity.observability.IdentityMetrics
import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.event.Level

fun Application.module() {
    val identityConfig = IdentityConfig.from(environment.config)
    val database = DatabaseFactory(identityConfig.database)
    val redis = RedisRateLimiter(identityConfig.redisUrl)
    val jwtService = JwtService(identityConfig.jwt)
    val metrics = IdentityMetrics()
    val repository = IdentityRepository(database.dataSource)
    val outboxPublisher = OutboxPublisher(repository, identityConfig.kafka)
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    outboxPublisher.start(backgroundScope)
    val identityService = IdentityService(
        repository = repository,
        passwordHasher = PasswordHasher(),
        jwtService = jwtService,
        rateLimiter = redis,
        delivery = identityConfig.challengeDelivery.resendApiKey?.takeIf { it.isNotBlank() }?.let { resendKey ->
            val msg91AuthKey = identityConfig.challengeDelivery.msg91AuthKey?.takeIf { it.isNotBlank() }
            val msg91TemplateId = identityConfig.challengeDelivery.msg91TemplateId?.takeIf { it.isNotBlank() }
            MultiChannelChallengeDelivery(
                email = ResendEmailProvider(resendKey, identityConfig.challengeDelivery.resendFromAddress),
                smsFallback = HttpChallengeDelivery(identityConfig.challengeDelivery),
                sms = if (msg91AuthKey != null && msg91TemplateId != null) Msg91SmsProvider(msg91AuthKey, msg91TemplateId) else null,
            )
        } ?: HttpChallengeDelivery(identityConfig.challengeDelivery),
        oauthVerifier = ConfiguredOAuthIdentityVerifier(identityConfig.oauth),
        security = identityConfig.security,
        refreshTokenDays = identityConfig.jwt.refreshTokenDays,
        metrics = metrics,
    )

    monitor.subscribe(ApplicationStopping) {
        outboxPublisher.close()
        backgroundScope.cancel()
        redis.close()
        database.close()
    }

    install(DefaultHeaders)
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { it.length in 8..128 }
        generate { "req_${java.util.UUID.randomUUID()}" }
    }
    install(CallLogging) {
        level = Level.INFO
        mdc("requestId") { it.callId }
        mdc("traceId") { it.request.header("traceparent").orEmpty() }
    }
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty()))
        }
    }
    install(CORS) {
        allowHost("localhost:3000")
        allowHost("localhost:8080")
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.XRequestId)
        allowHeader("Idempotency-Key")
        allowCredentials = true
    }
    routing {
        get("/health/live") { call.respond(Health("UP", "identity-service")) }
        get("/health/ready") {
            val ready = runCatching { database.ping() && redis.ping() }.getOrDefault(false)
            if (ready) call.respond(Health("UP", "identity-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "identity-service"))
        }
        get("/metrics") {
            call.respondText("# HELP identity_service_up Identity service process health\n# TYPE identity_service_up gauge\nidentity_service_up 1\n${metrics.prometheus()}", ContentType.Text.Plain)
        }
        identityRoutes(identityService, jwtService, identityConfig.internalServiceToken)
    }
}

@Serializable
private data class Health(val status: String, val component: String)
