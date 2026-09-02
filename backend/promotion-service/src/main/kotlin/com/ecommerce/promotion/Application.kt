package com.ecommerce.promotion

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
import io.ktor.server.request.header
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
import java.security.MessageDigest
import java.time.Duration

interface PromotionStore {
    fun listForSeller(sellerId: String, limit: Int): List<PromotionResponse>
    fun create(input: PromotionRequest, actorId: String, correlationId: String): PromotionResponse
    fun update(id: String, input: PromotionRequest, actorId: String, correlationId: String): PromotionResponse
    fun archive(id: String, actorId: String, correlationId: String): PromotionResponse
    fun createCoupon(input: CouponRequest, actorId: String, correlationId: String): CouponResponse
    fun updateCoupon(id: String, input: CouponUpdateRequest, actorId: String, correlationId: String): CouponResponse
    fun disableCoupon(id: String, actorId: String, correlationId: String): CouponResponse
    fun calculate(userId: String?, input: PromotionCalculateRequest): PromotionQuote
    fun apply(userId: String, input: PromotionCalculateRequest, key: String, orderId: String?, correlationId: String): RedemptionResponse
    fun transition(userId: String, id: String, target: RedemptionStatus, correlationId: String): RedemptionResponse
}

interface PromotionCache {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
}

private class PromotionRedisCache(private val delegate: RedisCache) : PromotionCache {
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: Duration) = delegate.put(key, value, ttl)
}

fun Application.module() {
    val config = environment.config
    val database = ServiceDatabase(ServiceDatabaseConfig(config.required("promotion.database.url"), config.required("promotion.database.username"), config.required("promotion.database.password"), config.required("promotion.database.maximumPoolSize").toInt(), config.required("promotion.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val redis = RedisCache(config.required("promotion.redis.url"))
    val repository = PromotionRepository(database.dataSource())
    val verifier = HmacJwtAccessVerifier(config.required("promotion.jwt.issuer"), config.required("promotion.jwt.audience"), parseKeys(config.required("promotion.jwt.keys")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("promotion.kafka.bootstrapServers"), config.required("promotion.kafka.topic"), config.required("promotion.kafka.tenantId")), "promotion-service")
    publisher.start(scope)
    monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); redis.close(); database.close() }

    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowHeader("Idempotency-Key"); allowHeader("X-Internal-Service-Token"); allowHeader("X-Actor-Id"); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "promotion-service")) }
        get("/health/ready") { if (runCatching { database.ping() && redis.ping() }.getOrDefault(false)) call.respond(Health("UP", "promotion-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "promotion-service")) }
        get("/metrics") { call.respondText("# TYPE promotion_requests_total counter\npromotion_requests_total 1\n", ContentType.Text.Plain) }
    }
    configurePromotionRoutes(repository, PromotionRedisCache(redis), verifier, config.required("promotion.internalToken"))
}

fun Application.configurePromotionRoutes(store: PromotionStore, cache: PromotionCache, verifier: HmacJwtAccessVerifier, internalToken: String) {
    routing {
        get("/api/v1/internal/promotions") { call.requireInternal(internalToken); val sellerId = call.request.queryParameters["sellerId"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "sellerId is required.", 400); call.respond(store.listForSeller(sellerId, call.request.queryParameters["limit"]?.toIntOrNull() ?: 50)) }
        post("/api/v1/promotions/quote") { val request = call.receive<PromotionCalculateRequest>(); call.respond(cachedQuote(cache, request) { store.calculate(null, request) }) }
        post("/api/v1/promotions/validate") { val request = call.receive<PromotionCalculateRequest>(); call.respond(cachedQuote(cache, request) { store.calculate(null, request) }) }
        post("/api/v1/promotions/apply") { val principal = call.requireAuth(verifier); val request = call.receive<RedemptionRequest>(); call.respond(HttpStatusCode.Created, store.apply(principal.subject, request.request, call.idempotency(), request.orderId, call.callId.orEmpty())) }
        post("/api/v1/internal/promotions/apply") { call.requireInternal(internalToken); val request = call.receive<InternalRedemptionRequest>(); call.respond(HttpStatusCode.Created, store.apply(request.userId, request.request, call.idempotency(), request.orderId, call.callId.orEmpty())) }
        post("/api/v1/internal/promotions/redemptions/{redemptionId}/commit") { call.requireInternal(internalToken); call.respond(store.transition(call.request.header("X-Actor-Id") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "X-Actor-Id is required.", 400), call.parameters.required("redemptionId"), RedemptionStatus.COMMITTED, call.callId.orEmpty())) }
        post("/api/v1/internal/promotions/redemptions/{redemptionId}/release") { call.requireInternal(internalToken); call.respond(store.transition(call.request.header("X-Actor-Id") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "X-Actor-Id is required.", 400), call.parameters.required("redemptionId"), RedemptionStatus.RELEASED, call.callId.orEmpty())) }
        post("/api/v1/promotions/remove") { val principal = call.requireAuth(verifier); val request = call.receive<RemoveRedemptionRequest>(); call.respond(store.transition(principal.subject, request.redemptionId, RedemptionStatus.RELEASED, call.callId.orEmpty())) }
        post("/api/v1/promotions/redemptions/{redemptionId}/release") { val principal = call.requireAuth(verifier); call.respond(store.transition(principal.subject, call.parameters.required("redemptionId"), RedemptionStatus.RELEASED, call.callId.orEmpty())) }
        post("/api/v1/promotions/redemptions/{redemptionId}/commit") { val principal = call.requireAuth(verifier); call.respond(store.transition(principal.subject, call.parameters.required("redemptionId"), RedemptionStatus.COMMITTED, call.callId.orEmpty())) }
        post("/api/v1/admin/promotions") { val principal = call.requirePermission(verifier, "PROMOTION_CREATE"); call.respond(HttpStatusCode.Created, store.create(call.receive(), principal.subject, call.callId.orEmpty())) }
        patch("/api/v1/admin/promotions/{promotionId}") { val principal = call.requirePermission(verifier, "PROMOTION_UPDATE"); call.respond(store.update(call.parameters.required("promotionId"), call.receive(), principal.subject, call.callId.orEmpty())) }
        delete("/api/v1/admin/promotions/{promotionId}") { val principal = call.requirePermission(verifier, "PROMOTION_DELETE"); call.respond(store.archive(call.parameters.required("promotionId"), principal.subject, call.callId.orEmpty())) }
        post("/api/v1/admin/coupons") { val principal = call.requirePermission(verifier, "COUPON_CREATE"); call.respond(HttpStatusCode.Created, store.createCoupon(call.receive(), principal.subject, call.callId.orEmpty())) }
        patch("/api/v1/admin/coupons/{couponId}") { val principal = call.requirePermission(verifier, "COUPON_UPDATE"); call.respond(store.updateCoupon(call.parameters.required("couponId"), call.receive(), principal.subject, call.callId.orEmpty())) }
        delete("/api/v1/admin/coupons/{couponId}") { val principal = call.requirePermission(verifier, "COUPON_DELETE"); call.respond(store.disableCoupon(call.parameters.required("couponId"), principal.subject, call.callId.orEmpty())) }
    }
}

@Serializable private data class Health(val status: String, val component: String)
@Serializable internal data class RedemptionRequest(val request: PromotionCalculateRequest, val orderId: String? = null)
@Serializable internal data class InternalRedemptionRequest(val userId: String, val request: PromotionCalculateRequest, val orderId: String? = null)
@Serializable private data class RemoveRedemptionRequest(val redemptionId: String)
private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.idempotency() = request.header("Idempotency-Key")?.takeIf { it.isNotBlank() } ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required.", 400)
private fun io.ktor.server.application.ApplicationCall.requireAuth(verifier: HmacJwtAccessVerifier): VerifiedAccessToken = verifier.verify(request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401))
private fun io.ktor.server.application.ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val principal = requireAuth(verifier); if (!(principal.isPrivileged() || permission in principal.permissions)) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403); return principal }
private fun io.ktor.server.application.ApplicationCall.requireInternal(expected: String) { if (expected.isBlank() || request.header("X-Internal-Service-Token") != expected) throw ApiException(ErrorCode.FORBIDDEN, "Internal service authentication failed.", 403) }
private fun VerifiedAccessToken.isPrivileged() = "ADMIN" in roles || "SUPER_ADMIN" in roles
private fun io.ktor.http.Parameters.required(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
private fun cachedQuote(redis: PromotionCache, request: PromotionCalculateRequest, loader: () -> PromotionQuote): PromotionQuote { val key = "promotion:quote:" + MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(request).toByteArray()).joinToString("") { "%02x".format(it) }; return redis.get(key)?.let { runCatching { Json.decodeFromString<PromotionQuote>(it) }.getOrNull() } ?: loader().also { redis.put(key, Json.encodeToString(it), Duration.ofSeconds(10)) } }
