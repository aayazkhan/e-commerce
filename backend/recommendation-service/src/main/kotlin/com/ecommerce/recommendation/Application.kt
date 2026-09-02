package com.ecommerce.recommendation

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.kafka.KafkaConsumerWorker
import com.ecommerce.platform.service.RedisCache
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration

interface RecommendationStore {
    fun record(request: BehaviorRequest): Int?
    fun recommendations(type: RecommendationType, userId: String?, productId: String?, limit: Int): List<String>
    fun popular(limit: Int): List<String>
}

interface RecommendationCache {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
}

private class RecommendationRedisCache(private val delegate: RedisCache) : RecommendationCache {
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: Duration) = delegate.put(key, value, ttl)
}

fun Application.module() {
    val c = environment.config
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val db = ServiceDatabase(ServiceDatabaseConfig(c.required("recommendation.database.url"), c.required("recommendation.database.username"), c.required("recommendation.database.password"), c.required("recommendation.database.maximumPoolSize").toInt(), c.required("recommendation.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val repo = RecommendationRepository(db.dataSource(), json)
    val cache = RedisCache(c.required("recommendation.redis.url"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val worker = if (c.required("recommendation.kafka.bootstrapServers").isNotBlank()) KafkaConsumerWorker(c.required("recommendation.kafka.bootstrapServers"), c.required("recommendation.kafka.groupId"), c.required("recommendation.kafka.topics").split(',').map(String::trim).filter(String::isNotBlank), "recommendation-service", { repo.accept(it) }, { event, error -> repo.dlq(event, error) }).also { it.start(scope) } else null
    monitor.subscribe(ApplicationStopping) { worker?.close(); scope.cancel(); cache.close(); db.close() }
    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId } }
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.statusCode), ApiError(e.errorCode, e.message, call.callId.orEmpty(), e.fieldViolations, e.retryable)) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "recommendation-service")) }
        get("/health/ready") { if (runCatching { db.ping() }.getOrDefault(false)) call.respond(Health("UP", "recommendation-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "recommendation-service")) }
        get("/metrics") { call.respondText("recommendation_cache_fallback_total 0\nrecommendation_consumer_lag_observed ${worker?.lagObserved?.get() ?: 0}\nrecommendation_consumer_failures ${worker?.failuresObserved?.get() ?: 0}\n", ContentType.Text.Plain) }
    }
    configureRecommendationRoutes(repo, RecommendationRedisCache(cache), json)
}

fun Application.configureRecommendationRoutes(store: RecommendationStore, cache: RecommendationCache, json: Json) {
    routing {
        post("/api/v1/recommendations/events") { store.record(call.receive()); call.respond(HttpStatusCode.Accepted) }
        get("/api/v1/recommendations") {
            val type = runCatching { RecommendationType.valueOf(call.request.queryParameters["type"]?.uppercase() ?: "TRENDING") }.getOrElse { throw ApiException(ErrorCode.VALIDATION_ERROR, "Unknown recommendation type.", 400) }
            val user = call.request.queryParameters["userId"]
            val product = call.request.queryParameters["productId"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val key = "recommendation:${type.name}:${user ?: "anon"}:$product:$limit"
            val cached = cache.get(key)
            if (cached != null) call.respond(json.decodeFromString<RecommendationResponse>(cached).copy(cached = true))
            else {
                val ids = runCatching { store.recommendations(type, user, product, limit) }.getOrElse { emptyList() }
                val fallback = if (ids.isEmpty()) store.popular(limit) else ids
                val result = RecommendationResponse(type, fallback, if (ids.isEmpty()) "popular-fallback" else "rules")
                cache.put(key, json.encodeToString(result), Duration.ofSeconds(60))
                call.respond(result)
            }
        }
        get("/api/v1/recommendations/fallback") { call.respond(RecommendationResponse(RecommendationType.TRENDING, store.popular(call.request.queryParameters["limit"]?.toIntOrNull() ?: 20), "popular-fallback")) }
    }
}

private fun ApplicationConfig.required(path: String) = property(path).getString()
@kotlinx.serialization.Serializable private data class Health(val status: String, val component: String)
