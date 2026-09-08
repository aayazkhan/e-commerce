package com.ecommerce.inventory
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
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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

interface InventoryStore {
    fun findByVariant(variantId: String): List<InventoryItem>
    fun reserve(input: ReservationInput, correlationId: String): Reservation
    fun getReservation(id: String): Reservation?
    fun release(id: String, actorId: String, correlationId: String, expired: Boolean): Reservation
    fun commit(id: String, actorId: String, correlationId: String): Reservation
    fun createItem(input: InventoryItemInput, actorId: String, correlationId: String): InventoryItem
    fun adjust(itemId: String, delta: Long, type: AdjustmentType, reason: String, actorId: String, correlationId: String): InventoryItem
    fun movements(variantId: String, limit: Int): List<InventoryMovement>
}

fun Application.module() {
    val config = environment.config
    val database = ServiceDatabase(
        ServiceDatabaseConfig(
            config.required("inventory.database.url"),
            config.required("inventory.database.username"),
            config.required("inventory.database.password"),
            config.required("inventory.database.maximumPoolSize").toInt(),
            config.required("inventory.database.connectionTimeoutMillis").toLong(),
        ),
        "classpath:db/migration",
    )
    val redis = RedisCache(config.required("inventory.redis.url"))
    val repository = InventoryRepository(database.dataSource())
    val verifier = HmacJwtAccessVerifier(
        config.required("inventory.jwt.issuer"),
        config.required("inventory.jwt.audience"),
        parseKeys(config.required("inventory.jwt.keys")),
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val publisher = KafkaOutboxPublisher(
        repository,
        ServiceKafkaConfig(
            config.required("inventory.kafka.bootstrapServers"),
            config.required("inventory.kafka.topic"),
            config.required("inventory.kafka.tenantId"),
        ),
        "inventory-service",
    )
    publisher.start(scope)
    scope.launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { repository.expireBatch(100, "inventory-expiry-worker") }
            delay(config.required("inventory.expirationIntervalSeconds").toLong() * 1_000)
        }
    }
    monitor.subscribe(ApplicationStopping) {
        scope.coroutineContext.cancel()
        publisher.close()
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
        mdc("traceId") { it.request.headers["traceparent"].orEmpty() }
    }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable))
        }
        exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause);
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
        get("/health/live") { call.respond(Health("UP", "inventory-service")) }
        get("/health/ready") { if (runCatching { database.ping() && redis.ping() }.getOrDefault(false)) call.respond(Health("UP", "inventory-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "inventory-service")) }
        get("/metrics") { call.respondText("# TYPE inventory_requests_total counter\ninventory_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureInventoryRoutes(InventoryRepositoryAdapter(repository), verifier, config.required("inventory.internalToken"))
}

fun Application.configureInventoryRoutes(repository: InventoryStore, verifier: HmacJwtAccessVerifier, internalToken: String) {
    routing {
        get("/api/v1/inventory/items/{variantId}") { call.respond(repository.findByVariant(call.parameters.required("variantId"))) }
        post("/api/v1/inventory/reservations") { val principal = call.requirePermissionOrInternal(verifier, "INVENTORY_RESERVE", internalToken); val request = call.receive<ReservationRequest>(); val reservation = repository.reserve(ReservationInput(request.reservationKey, principal.subject, request.cartId, request.orderId, request.items, request.ttlSeconds), call.callId.orEmpty()); call.respond(HttpStatusCode.Created, reservation) }
        get("/api/v1/inventory/reservations/{reservationId}") { val principal = call.requirePermission(verifier, "INVENTORY_READ"); val reservation = repository.getReservation(call.parameters.required("reservationId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Reservation not found.", 404); if (reservation.actorId != principal.subject && !principal.isPrivileged()) throw ApiException(ErrorCode.FORBIDDEN, "You do not own this reservation.", 403); call.respond(reservation) }
        post("/api/v1/inventory/reservations/{reservationId}/release") { val principal = call.requirePermissionOrInternal(verifier, "INVENTORY_RESERVE", internalToken); call.respond(repository.release(call.parameters.required("reservationId"), principal.subject, call.callId.orEmpty(), false)) }
        post("/api/v1/inventory/reservations/{reservationId}/commit") { val principal = call.requirePermissionOrInternal(verifier, "INVENTORY_COMMIT", internalToken); call.respond(repository.commit(call.parameters.required("reservationId"), principal.subject, call.callId.orEmpty())) }
        post("/api/v1/admin/inventory/items") { val principal = call.requirePermission(verifier, "INVENTORY_ADJUST"); val request = call.receive<InventoryItemRequest>(); call.respond(HttpStatusCode.Created, repository.createItem(request.input(), principal.subject, call.callId.orEmpty())) }
        post("/api/v1/admin/inventory/adjustments") { val principal = call.requirePermission(verifier, "INVENTORY_ADJUST"); val request = call.receive<AdjustmentRequest>(); call.respond(repository.adjust(request.itemId, request.quantityDelta, request.type, request.reason, principal.subject, call.callId.orEmpty())) }
        get("/api/v1/admin/inventory/{variantId}") { call.requirePermission(verifier, "INVENTORY_READ"); call.respond(repository.findByVariant(call.parameters.required("variantId"))) }
        get("/api/v1/admin/inventory/{variantId}/movements") { call.requirePermission(verifier, "INVENTORY_READ"); call.respond(repository.movements(call.parameters.required("variantId"), call.request.queryParameters["limit"]?.toIntOrNull() ?: 50)) }
    }
}

private class InventoryRepositoryAdapter(private val delegate: InventoryRepository) : InventoryStore {
    override fun findByVariant(variantId: String) = delegate.findByVariant(variantId)
    override fun reserve(input: ReservationInput, correlationId: String) = delegate.reserve(input, correlationId)
    override fun getReservation(id: String) = delegate.getReservation(id)
    override fun release(id: String, actorId: String, correlationId: String, expired: Boolean) = delegate.release(id, actorId, correlationId, expired)
    override fun commit(id: String, actorId: String, correlationId: String) = delegate.commit(id, actorId, correlationId)
    override fun createItem(input: InventoryItemInput, actorId: String, correlationId: String) = delegate.createItem(input, actorId, correlationId)
    override fun adjust(itemId: String, delta: Long, type: AdjustmentType, reason: String, actorId: String, correlationId: String) = delegate.adjust(itemId, delta, type, reason, actorId, correlationId)
    override fun movements(variantId: String, limit: Int) = delegate.movements(variantId, limit)
}

@Serializable
private data class Health(val status: String, val component: String)

@Serializable
data class ReservationRequest(
    val reservationKey: String,
    val cartId: String? = null,
    val orderId: String? = null,
    val items: List<ReservationItem>,
    val ttlSeconds: Long = 900,
)

@Serializable
data class InventoryItemRequest(
    val warehouseId: String = "",
    val warehouseCode: String? = null,
    val warehouseName: String? = null,
    val productId: String,
    val variantId: String,
    val onHand: Long,
    val lowStockThreshold: Long = 0,
) {
    fun input() = InventoryItemInput(warehouseId, warehouseCode, warehouseName, productId, variantId, onHand, lowStockThreshold)
}

@Serializable
data class AdjustmentRequest(val itemId: String, val quantityDelta: Long, val type: AdjustmentType, val reason: String)

private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken {
    val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401)
    val principal = verifier.verify(token)
    if (!principal.isPrivileged() && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403)
    return principal
}
private fun io.ktor.server.application.ApplicationCall.requirePermissionOrInternal(verifier: HmacJwtAccessVerifier, permission: String, expectedToken: String): VerifiedAccessToken {
    if (expectedToken.isNotBlank() && request.header("X-Internal-Service-Token") == expectedToken) return VerifiedAccessToken(request.header("X-Actor-Id") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "X-Actor-Id is required for internal inventory operations.", 400), setOf("INTERNAL"), setOf(permission), "internal")
    return requirePermission(verifier, permission)
}
private fun VerifiedAccessToken.isPrivileged() = "ADMIN" in roles || "SUPER_ADMIN" in roles || "WAREHOUSE" in roles
private fun io.ktor.http.Parameters.required(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
