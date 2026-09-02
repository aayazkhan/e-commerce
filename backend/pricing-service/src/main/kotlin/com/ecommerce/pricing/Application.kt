package com.ecommerce.pricing

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
import java.time.Duration
import java.time.Instant

interface PricingStore {
    fun current(productId: String, variantId: String?, currency: String, region: String, segment: String): Price?
    fun quote(items: List<QuoteItem>, currency: String, region: String, segment: String): PriceQuote
    fun create(input: PriceInput, actorId: String, correlationId: String): Price
    fun update(id: String, input: PriceInput, actorId: String, correlationId: String): Price
    fun delete(id: String, actorId: String, correlationId: String): Int
}

interface PricingCache {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
    fun delete(vararg keys: String)
}

fun Application.module() {
    val config = environment.config; val database = ServiceDatabase(ServiceDatabaseConfig(config.required("pricing.database.url"), config.required("pricing.database.username"), config.required("pricing.database.password")), "classpath:db/migration"); val redis = RedisCache(config.required("pricing.redis.url")); val repository = PricingRepository(database.dataSource()); val verifier = HmacJwtAccessVerifier(config.required("pricing.jwt.issuer"), config.required("pricing.jwt.audience"), parseKeys(config.required("pricing.jwt.keys"))); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("pricing.kafka.bootstrapServers"), config.required("pricing.kafka.topic"), config.required("pricing.kafka.tenantId")), "pricing-service"); publisher.start(scope); monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); redis.close(); database.close() }
    install(DefaultHeaders); install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }; install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }; install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }; install(StatusPages) { exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }; exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) } }; install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "pricing-service")) }
        get("/health/ready") { if (runCatching { database.ping() && redis.ping() }.getOrDefault(false)) call.respond(Health("UP", "pricing-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "pricing-service")) }
        get("/metrics") { call.respondText("# TYPE pricing_requests_total counter\npricing_requests_total 1\n", ContentType.Text.Plain) }
    }
    configurePricingRoutes(repository, PricingCacheAdapter(redis), verifier)
}

fun Application.configurePricingRoutes(repository: PricingStore, redis: PricingCache, verifier: HmacJwtAccessVerifier) {
    routing {
        get("/api/v1/pricing/products/{productId}") { val currency = call.request.queryParameters["currency"] ?: "INR"; val region = call.request.queryParameters["country"] ?: "IN"; val variantId = call.request.queryParameters["variantId"]; val segment = call.request.queryParameters["segment"] ?: "DEFAULT"; val productId = call.parameters.requireValue("productId"); call.respond(cachedPrice(redis, "pricing:$productId:${variantId.orEmpty()}:$currency:$region:$segment") { repository.current(productId, variantId, currency, region, segment) } ?: throw ApiException(ErrorCode.NOT_FOUND, "Price not found.", 404)) }
        post("/api/v1/pricing/quote") { val request = call.receive<QuoteRequest>(); call.respond(repository.quote(request.items.map { QuoteItem(it.productId, it.variantId, it.quantity) }, request.currency, request.country, request.customerSegment)) }
        route("/api/v1/admin/pricing") {
            post { val principal = call.requirePermission(verifier, "PRICE_CREATE"); val price = repository.create(call.receive<PriceRequest>().input(), principal.subject, call.callId.orEmpty()); call.respond(HttpStatusCode.Created, price) }
            patch("/{priceId}") { val principal = call.requirePermission(verifier, "PRICE_UPDATE"); val id = call.parameters.requireValue("priceId"); val price = repository.update(id, call.receive<PriceRequest>().input(), principal.subject, call.callId.orEmpty()); redis.delete("pricing:${price.productId}:${price.variantId.orEmpty()}:${price.currency}:${price.region}:${price.customerSegment}"); call.respond(price) }
            delete("/{priceId}") { val principal = call.requirePermission(verifier, "PRICE_DELETE"); repository.delete(call.parameters.requireValue("priceId"), principal.subject, call.callId.orEmpty()); call.respond(Message("Price retired.")) }
        }
    }
}

private class PricingCacheAdapter(private val delegate: RedisCache) : PricingCache {
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: Duration) = delegate.put(key, value, ttl)
    override fun delete(vararg keys: String) = delegate.delete(*keys)
}

@Serializable private data class Health(val status: String, val component: String)
@Serializable private data class Message(val message: String)
@Serializable data class QuoteRequest(val items: List<QuoteItemRequest>, val currency: String = "INR", val country: String = "IN", val customerSegment: String = "DEFAULT")
@Serializable data class QuoteItemRequest(val productId: String, val variantId: String? = null, val quantity: Int)
@Serializable data class PriceRequest(val productId: String, val variantId: String? = null, val sellerId: String? = null, val currency: String = "INR", val region: String = "IN", val customerSegment: String = "DEFAULT", val baseMinor: Long, val saleMinor: Long? = null, val taxRateBps: Int = 0, val effectiveFrom: String, val effectiveTo: String? = null) { fun input() = PriceInput(productId, variantId, sellerId, currency, region, customerSegment, baseMinor, saleMinor, taxRateBps, Instant.parse(effectiveFrom), effectiveTo?.let(Instant::parse)) }
private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private val pricingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
private fun cachedPrice(redis: PricingCache, key: String, loader: () -> Price?): Price? = redis.get(key)?.let { runCatching { pricingJson.decodeFromString<Price>(it) }.getOrNull() } ?: loader()?.also { redis.put(key, pricingJson.encodeToString(it), Duration.ofSeconds(10)) }
private fun ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val raw = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); val principal = verifier.verify(raw); if ("ADMIN" !in principal.roles && "SUPER_ADMIN" !in principal.roles && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403); return principal }
private fun Parameters.requireValue(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
