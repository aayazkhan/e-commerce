package com.ecommerce.flags
import io.ktor.server.application.log

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.requirePermission
import com.ecommerce.platform.service.KafkaOutboxPublisher
import com.ecommerce.platform.service.RedisCache
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import com.ecommerce.platform.service.ServiceKafkaConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration

interface FlagStore {
    fun list(): List<FeatureFlagResponse>
    fun get(key: String): FeatureFlagResponse?
    fun save(actor: String, request: FeatureFlagRequest, correlation: String): FeatureFlagResponse
    fun delete(actor: String, key: String, correlation: String)
    fun evaluate(request: EvaluationRequest): EvaluationResponse
}

interface FlagCache {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
}

private class FlagStoreAdapter(private val repository: FlagRepository) : FlagStore {
    override fun list() = repository.list()
    override fun get(key: String) = repository.get(key)
    override fun save(actor: String, request: FeatureFlagRequest, correlation: String) = repository.save(actor, request, correlation)
    override fun delete(actor: String, key: String, correlation: String) { repository.delete(actor, key, correlation) }
    override fun evaluate(request: EvaluationRequest) = repository.evaluate(request)
}

private class FlagRedisCache(private val delegate: RedisCache) : FlagCache {
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: Duration) = delegate.put(key, value, ttl)
}

fun Application.module() {
    val c = environment.config
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    val db = ServiceDatabase(ServiceDatabaseConfig(c.required("flags.database.url"), c.required("flags.database.username"), c.required("flags.database.password"), c.required("flags.database.maximumPoolSize").toInt(), c.required("flags.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val redis = RedisCache(c.required("flags.redis.url"))
    val repo = FlagRepository(db.dataSource(), json)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val publisher = KafkaOutboxPublisher(repo, ServiceKafkaConfig(c.required("flags.kafka.bootstrapServers"), c.required("flags.kafka.topic"), c.required("flags.kafka.tenantId")), "feature-flag-service")
    publisher.start(scope)
    monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); redis.close(); db.close() }
    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId } }
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.statusCode), ApiError(e.errorCode, e.message, call.callId.orEmpty(), e.fieldViolations, e.retryable)) }
        exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause); call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "feature-flag-service")) }
        get("/health/ready") { if (runCatching { db.ping() && redis.ping() }.getOrDefault(false)) call.respond(Health("UP", "feature-flag-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "feature-flag-service")) }
        get("/metrics") { call.respondText("feature_flag_evaluations_total 1\nfeature_flag_evaluation_errors 0\n", ContentType.Text.Plain) }
    }
    configureFlagRoutes(FlagStoreAdapter(repo), FlagRedisCache(redis), verifier(c), json)
}

fun Application.configureFlagRoutes(store: FlagStore, cache: FlagCache, verifier: HmacJwtAccessVerifier, json: Json) {
    routing {
        post("/api/v1/feature-flags/evaluate") {
            val request = call.receive<EvaluationRequest>()
            val cacheKey = "feature-flag:${request.key}:${request.environment}:${request.userId}:${request.sellerId}:${request.country}:${request.platform}:${request.appVersion}"
            val result = cache.get(cacheKey)?.let { runCatching { json.decodeFromString<EvaluationResponse>(it) }.getOrNull() }
                ?: runCatching { store.evaluate(request) }.getOrElse { EvaluationResponse(request.key, request.fallbackValue, false, 0, "safe-default") }.also { cache.put(cacheKey, json.encodeToString(it), Duration.ofSeconds(30)) }
            call.respond(result)
        }
        get("/api/v1/admin/feature-flags") { call.requirePermission(verifier, "ADMIN_FEATURE_FLAG_READ"); call.respond(store.list()) }
        post("/api/v1/admin/feature-flags") { val principal = call.requirePermission(verifier, "ADMIN_FEATURE_FLAG_UPDATE"); call.respond(HttpStatusCode.Created, store.save(principal.subject, call.receive(), call.callId.orEmpty())) }
        get("/api/v1/admin/feature-flags/{key}") { call.requirePermission(verifier, "ADMIN_FEATURE_FLAG_READ"); call.respond(store.get(call.parameters["key"]!!) ?: throw ApiException(ErrorCode.NOT_FOUND, "Feature flag not found.", 404)) }
        patch("/api/v1/admin/feature-flags/{key}") { val principal = call.requirePermission(verifier, "ADMIN_FEATURE_FLAG_UPDATE"); val request = call.receive<FeatureFlagRequest>(); if (request.key != call.parameters["key"]) throw ApiException(ErrorCode.VALIDATION_ERROR, "Flag key cannot change.", 400); call.respond(store.save(principal.subject, request, call.callId.orEmpty())) }
        delete("/api/v1/admin/feature-flags/{key}") { val principal = call.requirePermission(verifier, "ADMIN_FEATURE_FLAG_UPDATE"); store.delete(principal.subject, call.parameters["key"]!!, call.callId.orEmpty()); call.respond(HttpStatusCode.NoContent) }
    }
}

private fun ApplicationConfig.required(path: String) = property(path).getString()
private fun parseKeys(value: String) = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun verifier(c: ApplicationConfig) = HmacJwtAccessVerifier(c.required("flags.jwt.issuer"), c.required("flags.jwt.audience"), parseKeys(c.required("flags.jwt.keys")))
@Serializable private data class Health(val status: String, val component: String)
