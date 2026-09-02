package com.ecommerce.cms

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
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration

interface CmsStore {
    fun create(actor: String, request: CmsPageRequest, correlation: String): CmsPageResponse
    fun list(limit: Int): List<CmsPageResponse>
    fun get(id: String): CmsPageResponse?
    fun public(slug: String): CmsPageResponse?
    fun update(id: String, actor: String, request: CmsPageRequest, correlation: String): CmsPageResponse
    fun submit(id: String, actor: String, correlation: String): CmsPageResponse
    fun approve(id: String, actor: String, correlation: String): CmsPageResponse
    fun publish(id: String, actor: String, request: CmsPublishRequest, correlation: String): CmsPageResponse
    fun unpublish(id: String, actor: String, correlation: String): CmsPageResponse
    fun rollback(id: String, actor: String, request: CmsRollbackRequest, correlation: String): CmsPageResponse
}

interface CmsCache {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
    fun delete(key: String)
}

private class CmsRedisCache(private val delegate: RedisCache) : CmsCache {
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: Duration) = delegate.put(key, value, ttl)
    override fun delete(key: String) = delegate.delete(key)
}

fun Application.module() {
    val c = environment.config
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    val db = ServiceDatabase(ServiceDatabaseConfig(c.required("cms.database.url"), c.required("cms.database.username"), c.required("cms.database.password"), c.required("cms.database.maximumPoolSize").toInt(), c.required("cms.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val redis = RedisCache(c.required("cms.redis.url"))
    val repo = CmsRepository(db.dataSource(), json)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val publisher = KafkaOutboxPublisher(repo, ServiceKafkaConfig(c.required("cms.kafka.bootstrapServers"), c.required("cms.kafka.topic"), c.required("cms.kafka.tenantId")), "cms-service")
    publisher.start(scope)
    scope.launch { while (isActive) { runCatching { repo.publishDue() }; delay(5000) } }
    monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); redis.close(); db.close() }
    val verifier = HmacJwtAccessVerifier(c.required("cms.jwt.issuer"), c.required("cms.jwt.audience"), parseKeys(c.required("cms.jwt.keys")))
    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId } }
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.statusCode), ApiError(e.errorCode, e.message, call.callId.orEmpty(), e.fieldViolations, e.retryable)) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader("If-Match"); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "cms-service")) }
        get("/health/ready") { if (runCatching { db.ping() && redis.ping() }.getOrDefault(false)) call.respond(HttpStatusCode.OK, Health("UP", "cms-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "cms-service")) }
        get("/metrics") { call.respondText("cms_publish_total 1\ncms_publish_failures 0\n", ContentType.Text.Plain) }
    }
    configureCmsRoutes(repo, CmsRedisCache(redis), verifier, json)
}

fun Application.configureCmsRoutes(repository: CmsStore, cache: CmsCache, verifier: HmacJwtAccessVerifier, json: Json) {
    routing {
        get("/api/v1/cms/pages/{slug}") {
            val slug = call.parameters["slug"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page slug is required.", 400)
            val key = "cms:page:$slug"
            val cached = cache.get(key)
            if (cached != null) call.respond(json.decodeFromString<CmsPageResponse>(cached))
            else {
                val page = repository.public(slug) ?: throw ApiException(ErrorCode.NOT_FOUND, "Page not found.", 404)
                cache.put(key, json.encodeToString(page), Duration.ofSeconds(60))
                call.respond(page)
            }
        }
        post("/api/v1/admin/cms/pages") {
            val principal = call.requirePermission(verifier, "ADMIN_CMS_WRITE")
            val result = repository.create(principal.subject, call.receive(), call.callId.orEmpty())
            cache.delete("cms:page:${result.slug}")
            call.respond(HttpStatusCode.Created, result)
        }
        get("/api/v1/admin/cms/pages") {
            call.requirePermission(verifier, "ADMIN_CMS_READ")
            call.respond(repository.list(call.request.queryParameters["limit"]?.toIntOrNull() ?: 50))
        }
        get("/api/v1/admin/cms/pages/{id}") {
            call.requirePermission(verifier, "ADMIN_CMS_READ")
            call.respond(repository.get(call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page id is required.", 400)) ?: throw ApiException(ErrorCode.NOT_FOUND, "Page not found.", 404))
        }
        patch("/api/v1/admin/cms/pages/{id}") {
            val principal = call.requirePermission(verifier, "ADMIN_CMS_WRITE")
            val id = call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page id is required.", 400)
            val result = repository.update(id, principal.subject, call.receive(), call.callId.orEmpty())
            cache.delete("cms:page:${result.slug}")
            call.respond(result)
        }
        post("/api/v1/admin/cms/pages/{id}/submit") {
            val principal = call.requirePermission(verifier, "ADMIN_CMS_WRITE")
            call.respond(repository.submit(call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page id is required.", 400), principal.subject, call.callId.orEmpty()))
        }
        post("/api/v1/admin/cms/pages/{id}/approve") {
            val principal = call.requirePermission(verifier, "ADMIN_CMS_PUBLISH")
            call.respond(repository.approve(call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page id is required.", 400), principal.subject, call.callId.orEmpty()))
        }
        post("/api/v1/admin/cms/pages/{id}/publish") {
            val principal = call.requirePermission(verifier, "ADMIN_CMS_PUBLISH")
            val result = repository.publish(call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page id is required.", 400), principal.subject, call.receive(), call.callId.orEmpty())
            cache.delete("cms:page:${result.slug}")
            call.respond(result)
        }
        post("/api/v1/admin/cms/pages/{id}/unpublish") {
            val principal = call.requirePermission(verifier, "ADMIN_CMS_PUBLISH")
            val result = repository.unpublish(call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page id is required.", 400), principal.subject, call.callId.orEmpty())
            cache.delete("cms:page:${result.slug}")
            call.respond(result)
        }
        post("/api/v1/admin/cms/pages/{id}/rollback") {
            val principal = call.requirePermission(verifier, "ADMIN_CMS_WRITE")
            val result = repository.rollback(call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Page id is required.", 400), principal.subject, call.receive(), call.callId.orEmpty())
            cache.delete("cms:page:${result.slug}")
            call.respond(result)
        }
    }
}

private fun ApplicationConfig.required(path: String) = property(path).getString()
private fun parseKeys(value: String) = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
@Serializable private data class Health(val status: String, val component: String)
