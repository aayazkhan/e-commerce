package com.ecommerce.order
import io.ktor.server.application.log

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.VerifiedAccessToken
import com.ecommerce.platform.service.KafkaOutboxPublisher
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
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

interface OrderStore {
    fun create(userId: String, request: OrderCreateRequest, idempotencyKey: String, actorId: String, correlationId: String): OrderResponse
    fun getOwned(userId: String, id: String): OrderResponse?
    fun listOwned(userId: String, cursor: String?, limit: Int): Pair<List<OrderResponse>, String?>
    fun listAll(cursor: String?, limit: Int): Pair<List<OrderResponse>, String?>
    fun transition(id: String, userId: String?, target: OrderStatus, actorId: String, reason: String?, correlationId: String): OrderResponse
    fun cancel(id: String, userId: String, reason: String, actorId: String, correlationId: String): OrderResponse
    fun requestReturn(id: String, userId: String, request: ReturnRequest, actorId: String, correlationId: String): ReturnResponse
}

fun Application.module() {
    val config = environment.config
    val database = ServiceDatabase(ServiceDatabaseConfig(config.required("order.database.url"), config.required("order.database.username"), config.required("order.database.password"), config.required("order.database.maximumPoolSize").toInt(), config.required("order.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val repository = OrderRepository(database.dataSource())
    val verifier = HmacJwtAccessVerifier(config.required("order.jwt.issuer"), config.required("order.jwt.audience"), parseKeys(config.required("order.jwt.keys")))
    val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("order.kafka.bootstrapServers"), config.required("order.kafka.topic"), config.required("order.kafka.tenantId")), "order-service")
    publisher.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))
    monitor.subscribe(ApplicationStopping) { publisher.close(); database.close() }

    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.headers["traceparent"].orEmpty() } }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }
        exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause); call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowHeader("Idempotency-Key"); allowHeader("X-Internal-Service-Token"); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "order-service")) }
        get("/health/ready") { if (runCatching { database.ping() }.getOrDefault(false)) call.respond(Health("UP", "order-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "order-service")) }
        get("/metrics") { call.respondText("# TYPE order_requests_total counter\norder_requests_total 1\n", ContentType.Text.Plain) }

    }
    configureOrderRoutes(repository, verifier, config.required("order.internalToken"))
}

fun Application.configureOrderRoutes(repository: OrderStore, verifier: HmacJwtAccessVerifier, internalToken: String) {
    routing {
        post("/api/v1/orders") { val principal = call.requireUser(verifier); call.requireInternal(internalToken); val key = call.request.header("Idempotency-Key") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required.", 400); call.respond(HttpStatusCode.Created, repository.create(principal.subject, call.receive(), key, principal.subject, call.callId.orEmpty())) }
        get("/api/v1/orders") { val principal = call.requireUser(verifier); val page = repository.listOwned(principal.subject, call.request.queryParameters["cursor"], call.request.queryParameters["limit"]?.toIntOrNull() ?: 20); call.respond(CursorPage(page.first, page.second)) }
        get("/api/v1/orders/{orderId}") { val principal = call.requireUser(verifier); call.respond(repository.getOwned(principal.subject, call.parameters.required("orderId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Order not found.", 404)) }
        post("/api/v1/orders/{orderId}/cancel") { val principal = call.requireUser(verifier); call.respond(repository.cancel(call.parameters.required("orderId"), principal.subject, call.receive<CancelBody>().reason, principal.subject, call.callId.orEmpty())) }
        post("/api/v1/orders/{orderId}/returns") { val principal = call.requireUser(verifier); call.respond(HttpStatusCode.Created, repository.requestReturn(call.parameters.required("orderId"), principal.subject, call.receive(), principal.subject, call.callId.orEmpty())) }
        post("/api/v1/internal/orders/{orderId}/status") { call.requireInternal(internalToken); val request = call.receive<OrderStatusRequest>(); call.respond(repository.transition(call.parameters.required("orderId"), null, request.status, "internal", null, call.callId.orEmpty())) }
        get("/api/v1/admin/orders") { call.requirePermission(verifier, "ADMIN_ORDER_READ"); val page = repository.listAll(call.request.queryParameters["cursor"], call.request.queryParameters["limit"]?.toIntOrNull() ?: 50); call.respond(CursorPage(page.first, page.second)) }
        post("/api/v1/admin/orders/{orderId}/status") { val principal = call.requirePermission(verifier, "ADMIN_ORDER_UPDATE"); val request = call.receive<OrderStatusRequest>(); call.respond(repository.transition(call.parameters.required("orderId"), null, request.status, principal.subject, "admin", call.callId.orEmpty())) }
    }
}

@Serializable private data class Health(val status: String, val component: String)
@Serializable internal data class CancelBody(val reason: String = "customer-request")
@Serializable private data class CursorPage<T>(val items: List<T>, val nextCursor: String?)

private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.requireUser(verifier: HmacJwtAccessVerifier): VerifiedAccessToken { val token=request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED,"Authentication is required.",401); return verifier.verify(token) }
private fun io.ktor.server.application.ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val principal=requireUser(verifier); if (!principal.isPrivileged() && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN,"You do not have permission for this operation.",403); return principal }
private fun VerifiedAccessToken.isPrivileged() = setOf("ADMIN","SUPER_ADMIN","SUPPORT").any { it in roles }
private fun io.ktor.server.application.ApplicationCall.requireInternal(expected: String) { if (expected.isBlank() || request.header("X-Internal-Service-Token") != expected) throw ApiException(ErrorCode.FORBIDDEN,"Internal service authentication failed.",403) }
private fun io.ktor.http.Parameters.required(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR,"Missing path parameter: $name",400)
