package com.ecommerce.cart
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
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.security.SecureRandom

interface CartStore {
    fun getOrCreate(actor: CartActor, currency: String): CartResponse
    fun add(actor: CartActor, productId: String, variantId: String, quantity: Int, price: PriceSnapshot, key: String, correlationId: String): CartResponse
    fun update(actor: CartActor, variantId: String, quantity: Int, price: PriceSnapshot?, key: String, correlationId: String): CartResponse
    fun remove(actor: CartActor, variantId: String, currency: String, key: String, correlationId: String): CartResponse
    fun clear(actor: CartActor, currency: String, key: String, correlationId: String): CartResponse
    fun merge(userId: String, guestToken: String, currency: String, correlationId: String): CartResponse
}

interface CartPricing { fun current(productId: String, variantId: String, currency: String): PriceSnapshot }
interface CartInventory { fun available(variantId: String): Long }
interface CartCache {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: java.time.Duration)
    fun delete(vararg keys: String)
}

fun Application.module() {
    val config = environment.config
    val database = ServiceDatabase(ServiceDatabaseConfig(config.required("cart.database.url"), config.required("cart.database.username"), config.required("cart.database.password"), config.required("cart.database.maximumPoolSize").toInt(), config.required("cart.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val redis = RedisCache(config.required("cart.redis.url"))
    val repository = CartRepository(database.dataSource(), config.required("cart.guestExpirationSeconds").toLong(), config.required("cart.userExpirationSeconds").toLong())
    val pricing = PricingClient(config.required("cart.pricingBaseUrl"))
    val inventory = InventoryClient(config.required("cart.inventoryBaseUrl"))
    val verifier = HmacJwtAccessVerifier(config.required("cart.jwt.issuer"), config.required("cart.jwt.audience"), parseKeys(config.required("cart.jwt.keys")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("cart.kafka.bootstrapServers"), config.required("cart.kafka.topic"), config.required("cart.kafka.tenantId")), "cart-service")
    publisher.start(scope)
    scope.launch {
        while (isActive) {
            runCatching { repository.abandonExpired(100, "cart-expiry-worker") }
            kotlinx.coroutines.delay(60_000)
        }
    }
    monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); redis.close(); database.close() }

    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }
        exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause); call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowHeader("Idempotency-Key"); allowHeader("X-Guest-Token"); allowCredentials = true }

    routing {
        get("/health/live") { call.respond(Health("UP", "cart-service")) }
        get("/health/ready") { if (runCatching { database.ping() && redis.ping() }.getOrDefault(false)) call.respond(Health("UP", "cart-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "cart-service")) }
        get("/metrics") { call.respondText("# TYPE cart_requests_total counter\ncart_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureCartRoutes(CartRepositoryAdapter(repository), pricing, inventory, CartCacheAdapter(redis), verifier)
}

fun Application.configureCartRoutes(repository: CartStore, pricing: CartPricing, inventory: CartInventory, redis: CartCache, verifier: HmacJwtAccessVerifier) {
    routing {
        get("/api/v1/cart") { val actor = call.actor(verifier, true); val response = cached(redis, actor, call.currency()) { repository.getOrCreate(actor, call.currency()) }; call.respond(if (actor.userId == null && call.request.header("X-Guest-Token").isNullOrBlank()) response.copy(guestToken = actor.guestToken) else response) }
        post("/api/v1/cart/items") { val actor = call.actor(verifier, true); val request = call.receive<AddItemRequest>(); val price = pricing.current(request.productId, request.variantId, call.currency()); if (inventory.available(request.variantId) < request.quantity) throw ApiException(ErrorCode.CONFLICT, "Requested quantity is not currently available.", 409); val result = repository.add(actor, request.productId, request.variantId, request.quantity, price, call.idempotency(), call.callId.orEmpty()); invalidate(redis, actor); call.respond(HttpStatusCode.Created, result.withGuest(actor)) }
        patch("/api/v1/cart/items/{itemId}") { val actor = call.actor(verifier, false); val request = call.receive<UpdateItemRequest>(); val current = repository.getOrCreate(actor, call.currency()); val path = call.parameters.required("itemId"); val item = current.items.find { it.variantId == path || it.id == path } ?: throw ApiException(ErrorCode.NOT_FOUND, "Cart item not found.", 404); val price = pricing.current(item.productId, item.variantId, call.currency()); if (inventory.available(item.variantId) < request.quantity) throw ApiException(ErrorCode.CONFLICT, "Requested quantity is not currently available.", 409); val result = repository.update(actor, item.variantId, request.quantity, price, call.idempotency(), call.callId.orEmpty()); invalidate(redis, actor); call.respond(result.withGuest(actor)) }
        delete("/api/v1/cart/items/{itemId}") { val actor = call.actor(verifier, false); val current = repository.getOrCreate(actor, call.currency()); val path = call.parameters.required("itemId"); val item = current.items.find { it.variantId == path || it.id == path } ?: throw ApiException(ErrorCode.NOT_FOUND, "Cart item not found.", 404); val result = repository.remove(actor, item.variantId, call.currency(), call.idempotency(), call.callId.orEmpty()); invalidate(redis, actor); call.respond(result.withGuest(actor)) }
        delete("/api/v1/cart/items") { val actor = call.actor(verifier, false); val result = repository.clear(actor, call.currency(), call.idempotency(), call.callId.orEmpty()); invalidate(redis, actor); call.respond(result.withGuest(actor)) }
        post("/api/v1/cart/merge") { val principal = call.requireAuth(verifier); val request = call.receive<MergeRequest>(); val result = repository.merge(principal.subject, request.guestToken, call.currency(), call.callId.orEmpty()); invalidate(redis, CartActor(userId = principal.subject)); call.respond(result) }
        post("/api/v1/cart/validate") { val actor = call.actor(verifier, false); val cart = repository.getOrCreate(actor, call.currency()); val warnings = cart.items.flatMap { item -> val currentPrice = pricing.current(item.productId, item.variantId, cart.currency); val availability = inventory.available(item.variantId); buildList { if (currentPrice.unitMinor != item.unitPriceMinor) add(CartWarning(item.variantId, "PRICE_CHANGED", "The current price differs from the cart snapshot.", currentPrice.unitMinor)); if (availability < item.quantity) add(CartWarning(item.variantId, "INSUFFICIENT_STOCK", "The requested quantity is no longer available.")) } }; call.respond(CartValidation(cart, warnings.isEmpty(), warnings)) }
    }
}

internal class CartRepositoryAdapter(private val delegate: CartRepository) : CartStore {
    override fun getOrCreate(actor: CartActor, currency: String) = delegate.getOrCreate(actor, currency)
    override fun add(actor: CartActor, productId: String, variantId: String, quantity: Int, price: PriceSnapshot, key: String, correlationId: String) = delegate.add(actor, productId, variantId, quantity, price, key, correlationId)
    override fun update(actor: CartActor, variantId: String, quantity: Int, price: PriceSnapshot?, key: String, correlationId: String) = delegate.update(actor, variantId, quantity, price, key, correlationId)
    override fun remove(actor: CartActor, variantId: String, currency: String, key: String, correlationId: String) = delegate.remove(actor, variantId, currency, key, correlationId)
    override fun clear(actor: CartActor, currency: String, key: String, correlationId: String) = delegate.clear(actor, currency, key, correlationId)
    override fun merge(userId: String, guestToken: String, currency: String, correlationId: String) = delegate.merge(userId, guestToken, currency, correlationId)
}

private class CartCacheAdapter(private val delegate: RedisCache) : CartCache {
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: java.time.Duration) = delegate.put(key, value, ttl)
    override fun delete(vararg keys: String) = delegate.delete(*keys)
}

@Serializable internal data class Health(val status: String, val component: String)
@Serializable data class AddItemRequest(val productId: String, val variantId: String, val quantity: Int)
@Serializable data class UpdateItemRequest(val quantity: Int)
@Serializable data class MergeRequest(val guestToken: String)
internal fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
internal fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.currency() = request.queryParameters["currency"]?.uppercase() ?: request.header("X-Currency")?.uppercase() ?: "INR"
private fun io.ktor.server.application.ApplicationCall.idempotency() = request.header("Idempotency-Key")?.takeIf { it.isNotBlank() } ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required.", 400)
private fun io.ktor.server.application.ApplicationCall.actor(verifier: HmacJwtAccessVerifier, allowCreateGuest: Boolean): CartActor {
    val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
    if (!token.isNullOrBlank()) return CartActor(userId = verifier.verify(token).subject)
    val guest = request.header("X-Guest-Token") ?: if (allowCreateGuest) newGuestToken() else throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication or X-Guest-Token is required.", 401)
    if (guest.length !in 16..256) throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Guest token is invalid.", 401)
    return CartActor(guestToken = guest)
}
private fun io.ktor.server.application.ApplicationCall.requireAuth(verifier: HmacJwtAccessVerifier): VerifiedAccessToken = verifier.verify(request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401))
private fun io.ktor.http.Parameters.required(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
private fun newGuestToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
private fun CartResponse.withGuest(actor: CartActor) = if (actor.userId == null) copy(guestToken = actor.guestToken) else this
private fun cached(redis: CartCache, actor: CartActor, currency: String, loader: () -> CartResponse): CartResponse { val key = cacheKey(actor); return redis.get(key)?.let { runCatching { Json.decodeFromString<CartResponse>(it).takeIf { cart -> cart.currency == currency } }.getOrNull() } ?: loader().also { redis.put(key, Json.encodeToString(it), java.time.Duration.ofSeconds(15)) } }
private fun invalidate(redis: CartCache, actor: CartActor) { redis.delete(cacheKey(actor)) }
private fun cacheKey(actor: CartActor): String = "cart:" + java.security.MessageDigest.getInstance("SHA-256").digest((actor.userId ?: actor.guestToken!!).toByteArray()).joinToString("") { "%02x".format(it) }
