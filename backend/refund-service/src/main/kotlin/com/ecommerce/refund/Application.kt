package com.ecommerce.refund

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.VerifiedAccessToken
import com.ecommerce.platform.service.InternalHttpClient
import com.ecommerce.platform.service.KafkaOutboxPublisher
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import com.ecommerce.platform.service.ServiceKafkaConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

interface RefundStore {
    fun create(userId: String, request: RefundRequest, key: String, correlation: String): RefundResponse
    fun getOwned(userId: String, id: String): RefundResponse?
    fun listOwned(userId: String): List<RefundResponse>
    fun approve(id: String, decision: RefundDecision, actor: String, correlation: String): RefundResponse
}

fun Application.module() {
    val config = environment.config
    val db = ServiceDatabase(ServiceDatabaseConfig(config.required("refund.database.url"), config.required("refund.database.username"), config.required("refund.database.password"), config.required("refund.database.maximumPoolSize").toInt(), config.required("refund.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val token = config.required("refund.internalToken")
    val repo = RefundRepository(db.dataSource(), InternalHttpClient(config.required("refund.paymentBaseUrl")), mapOf("X-Internal-Service-Token" to token))
    val verifier = HmacJwtAccessVerifier(config.required("refund.jwt.issuer"), config.required("refund.jwt.audience"), parseKeys(config.required("refund.jwt.keys")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val publisher = KafkaOutboxPublisher(repo, ServiceKafkaConfig(config.required("refund.kafka.bootstrapServers"), config.required("refund.kafka.topic"), config.required("refund.kafka.tenantId")), "refund-service")
    publisher.start(scope)
    monitor.subscribe(ApplicationStopping) { scope.cancel(); publisher.close(); db.close() }
    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId } }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.statusCode), ApiError(e.errorCode, e.message, call.callId.orEmpty(), e.fieldViolations, e.retryable)) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader("Idempotency-Key"); allowHeader("X-Internal-Service-Token"); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "refund-service")) }
        get("/health/ready") { if (runCatching { db.ping() }.getOrDefault(false)) call.respond(Health("UP", "refund-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "refund-service")) }
        get("/metrics") { call.respondText("# TYPE refund_requests_total counter\nrefund_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureRefundRoutes(RefundStoreAdapter(repo), verifier, token)
}

private class RefundStoreAdapter(private val repository: RefundRepository) : RefundStore {
    override fun create(userId: String, request: RefundRequest, key: String, correlation: String) = repository.create(userId, request, key, correlation)
    override fun getOwned(userId: String, id: String) = repository.getOwned(userId, id)
    override fun listOwned(userId: String) = repository.listOwned(userId)
    override fun approve(id: String, decision: RefundDecision, actor: String, correlation: String) = repository.approve(id, decision, actor, correlation)
}

fun Application.configureRefundRoutes(store: RefundStore, verifier: HmacJwtAccessVerifier, internalToken: String) {
    routing {
        post("/api/v1/refunds") {
            val principal = call.requireUser(verifier)
            val key = call.request.header("Idempotency-Key") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required.", 400)
            call.respond(HttpStatusCode.Created, store.create(principal.subject, call.receive(), key, call.callId.orEmpty()))
        }
        get("/api/v1/refunds") { val principal = call.requireUser(verifier); call.respond(store.listOwned(principal.subject)) }
        get("/api/v1/refunds/{refundId}") {
            val principal = call.requireUser(verifier)
            call.respond(store.getOwned(principal.subject, call.parameters.required("refundId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Refund not found.", 404))
        }
        post("/api/v1/admin/refunds/{refundId}/decision") {
            val principal = call.requireUser(verifier)
            if (!principal.isPrivileged() && "ADMIN_REFUND_APPROVE" !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "Refund approval permission is required.", 403)
            call.respond(store.approve(call.parameters.required("refundId"), call.receive(), principal.subject, call.callId.orEmpty()))
        }
        post("/api/v1/internal/refunds/{refundId}/decision") {
            call.requireInternal(internalToken)
            call.respond(store.approve(call.parameters.required("refundId"), call.receive(), "internal", call.callId.orEmpty()))
        }
    }
}

@Serializable private data class Health(val status: String, val component: String)
private fun ApplicationConfig.required(path: String) = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String) = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.requireUser(verifier: HmacJwtAccessVerifier): VerifiedAccessToken { val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); return verifier.verify(token) }
private fun VerifiedAccessToken.isPrivileged() = setOf("ADMIN", "SUPER_ADMIN", "FINANCE").any { it in roles }
private fun io.ktor.server.application.ApplicationCall.requireInternal(expected: String) { if (expected.isBlank() || request.header("X-Internal-Service-Token") != expected) throw ApiException(ErrorCode.FORBIDDEN, "Internal service authentication failed.", 403) }
private fun io.ktor.http.Parameters.required(name: String) = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
