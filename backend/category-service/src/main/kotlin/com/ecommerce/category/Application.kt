package com.ecommerce.category
import io.ktor.server.application.log

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.VerifiedAccessToken
import com.ecommerce.platform.service.KafkaOutboxPublisher
import com.ecommerce.platform.service.RedisCache
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import com.ecommerce.platform.service.ServiceKafkaConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration

interface CategoryStore {
    fun list(parentId: String?, publicOnly: Boolean): List<Category>
    fun find(id: String, publicOnly: Boolean): Category?
    fun tree(): List<Category>
    fun create(input: CategoryInput, correlationId: String): Category
    fun update(id: String, input: CategoryInput, correlationId: String): Category
    fun delete(id: String, correlationId: String): Int
    fun changeStatus(id: String, status: CategoryStatus, correlationId: String): Category
    fun reorder(parentId: String?, ids: List<String>, correlationId: String): List<Category>
}

interface CategoryCache {
    fun ping(): Boolean
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
    fun delete(vararg keys: String)
}

fun Application.module() {
    val config = environment.config
    val database = ServiceDatabase(ServiceDatabaseConfig(config.required("category.database.url"), config.required("category.database.username"), config.required("category.database.password")), "classpath:db/migration")
    val redis = RedisCache(config.required("category.redis.url"))
    val repository = CategoryRepository(database.dataSource())
    val verifier = HmacJwtAccessVerifier(config.required("category.jwt.issuer"), config.required("category.jwt.audience"), parseKeys(config.required("category.jwt.keys")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("category.kafka.bootstrapServers"), config.required("category.kafka.topic"), config.required("category.kafka.tenantId")), "category-service")
    publisher.start(scope)
    monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); redis.close(); database.close() }
    install(DefaultHeaders); install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }; install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }; install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }
    install(StatusPages) { exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }; exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause); call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) } }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "category-service")) }
        get("/health/ready") { if (runCatching { database.ping() && redis.ping() }.getOrDefault(false)) call.respond(Health("UP", "category-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "category-service")) }
        get("/metrics") { call.respondText("# TYPE category_requests_total counter\ncategory_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureCategoryRoutes(repository, CategoryCacheAdapter(redis), verifier)
}

fun Application.configureCategoryRoutes(repository: CategoryStore, redis: CategoryCache, verifier: HmacJwtAccessVerifier) {
    routing {
        route("/api/v1/categories") {
            get { val parentId = call.request.queryParameters["parentId"]; call.respond(cachedCategories(redis, "category:list:${parentId ?: "root"}", 30) { repository.list(parentId, true) }) }
            get("/tree") { call.respond(cachedCategories(redis, "category:tree", 60) { repository.tree() }) }
            get("/{categoryId}") { call.respond(repository.find(call.parameters.requireValue("categoryId"), true) ?: throw ApiException(ErrorCode.NOT_FOUND, "Category not found.", 404)) }
            get("/{categoryId}/children") { call.respond(repository.list(call.parameters.requireValue("categoryId"), true)) }
        }
        route("/api/v1/admin/categories") {
            post { call.requirePermission(verifier, "CATEGORY_CREATE"); val category = repository.create(call.receive<CategoryRequest>().input(), call.callId.orEmpty()); redis.delete("category:tree"); call.respond(HttpStatusCode.Created, category) }
            patch("/{categoryId}") { call.requirePermission(verifier, "CATEGORY_UPDATE"); val category = repository.update(call.parameters.requireValue("categoryId"), call.receive<CategoryRequest>().input(), call.callId.orEmpty()); redis.delete("category:tree"); call.respond(category) }
            delete("/{categoryId}") { call.requirePermission(verifier, "CATEGORY_DELETE"); repository.delete(call.parameters.requireValue("categoryId"), call.callId.orEmpty()); redis.delete("category:tree"); call.respond(Message("Category deleted.")) }
            post("/{categoryId}/publish") { call.requirePermission(verifier, "CATEGORY_UPDATE"); val category = repository.changeStatus(call.parameters.requireValue("categoryId"), CategoryStatus.ACTIVE, call.callId.orEmpty()); redis.delete("category:tree"); call.respond(category) }
            post("/{categoryId}/unpublish") { call.requirePermission(verifier, "CATEGORY_UPDATE"); val category = repository.changeStatus(call.parameters.requireValue("categoryId"), CategoryStatus.INACTIVE, call.callId.orEmpty()); redis.delete("category:tree"); call.respond(category) }
            patch("/reorder") { call.requirePermission(verifier, "CATEGORY_UPDATE"); val request = call.receive<ReorderRequest>(); val categories = repository.reorder(request.parentId, request.categoryIds, call.callId.orEmpty()); redis.delete("category:tree"); call.respond(categories) }
        }
    }
}

private class CategoryCacheAdapter(private val delegate: RedisCache) : CategoryCache {
    override fun ping() = delegate.ping()
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: Duration) = delegate.put(key, value, ttl)
    override fun delete(vararg keys: String) = delegate.delete(*keys)
}

@Serializable private data class Health(val status: String, val component: String)
@Serializable private data class Message(val message: String)
@Serializable internal data class ReorderRequest(val parentId: String? = null, val categoryIds: List<String>)
@Serializable data class CategoryRequest(val parentId: String? = null, val name: String, val slug: String, val description: String? = null, val imageUrl: String? = null, val icon: String? = null, val sortOrder: Int = 0, val status: String = "DRAFT", val seoTitle: String? = null, val seoDescription: String? = null, val seoKeywords: List<String> = emptyList(), val canonicalUrl: String? = null) { fun input() = CategoryInput(parentId, name, slug, description, imageUrl, icon, sortOrder, CategoryStatus.valueOf(status.uppercase()), seoTitle, seoDescription, seoKeywords, canonicalUrl) }
private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { item -> item.substringBefore('=').trim() to item.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim(); if (token.isNullOrBlank()) throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); val principal = verifier.verify(token); if ("ADMIN" !in principal.roles && "SUPER_ADMIN" !in principal.roles && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403); return principal }
private fun Parameters.requireValue(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
private val categoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
private fun cachedCategories(redis: CategoryCache, key: String, ttlSeconds: Long, loader: () -> List<Category>): List<Category> = redis.get(key)?.let { runCatching { categoryJson.decodeFromString<List<Category>>(it) }.getOrNull() } ?: loader().also { redis.put(key, categoryJson.encodeToString(it), Duration.ofSeconds(ttlSeconds)) }
