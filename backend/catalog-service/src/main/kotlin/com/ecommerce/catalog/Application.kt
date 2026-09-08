package com.ecommerce.catalog

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
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration

interface CatalogStore {
    fun list(cursor: String?, limit: Int, categoryId: String?, sellerId: String?, status: ProductStatus?): Pair<List<Product>, String?>
    fun find(id: String, publicOnly: Boolean): Product?
    fun findBySlug(slug: String): Product?
    fun create(input: ProductInput, actorId: String, correlationId: String): Product
    fun update(id: String, input: ProductInput, actorId: String, correlationId: String): Product
    fun changeStatus(id: String, status: ProductStatus, actorId: String, correlationId: String): Product
    fun delete(id: String, actorId: String, correlationId: String): Int
}

interface CatalogCache {
    fun get(key: String): String?
    fun put(key: String, value: String, ttl: Duration)
    fun delete(vararg keys: String)
}

fun Application.module() {
    val config = environment.config
    val database = ServiceDatabase(ServiceDatabaseConfig(config.required("catalog.database.url"), config.required("catalog.database.username"), config.required("catalog.database.password"), 20), "classpath:db/migration")
    val redis = RedisCache(config.required("catalog.redis.url")); val repository = CatalogRepository(database.dataSource()); val verifier = HmacJwtAccessVerifier(config.required("catalog.jwt.issuer"), config.required("catalog.jwt.audience"), parseKeys(config.required("catalog.jwt.keys"))); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("catalog.kafka.bootstrapServers"), config.required("catalog.kafka.topic"), config.required("catalog.kafka.tenantId")), "catalog-service"); publisher.start(scope)
    monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); redis.close(); database.close() }
    install(DefaultHeaders); install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }; install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }; install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }; install(StatusPages) { exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }; exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause); call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) } }; install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowHeader("Idempotency-Key"); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "catalog-service")) }
        get("/health/ready") { if (runCatching { database.ping() && redis.ping() }.getOrDefault(false)) call.respond(Health("UP", "catalog-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "catalog-service")) }
        get("/metrics") { call.respondText("# TYPE catalog_requests_total counter\ncatalog_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureCatalogRoutes(repository, CatalogCacheAdapter(redis), verifier, config.required("catalog.internalToken"))
}

fun Application.configureCatalogRoutes(repository: CatalogStore, redis: CatalogCache, verifier: HmacJwtAccessVerifier, internalToken: String) {
    routing {
        route("/api/v1/products") {
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 24
                val cursor = call.request.queryParameters["cursor"]
                val categoryId = call.request.queryParameters["categoryId"]
                val sellerId = call.request.queryParameters["sellerId"]
                val key = "catalog:list:${cursor.orEmpty()}:${categoryId.orEmpty()}:${sellerId.orEmpty()}:ACTIVE:$limit"
                call.respond(cachedPage(redis, key) { val page = repository.list(cursor, limit, categoryId, sellerId, null); ProductPage(page.first, page.second, page.second != null) })
            }
            get("/slug/{slug}") { val slug = call.parameters.requireValue("slug"); call.respond(cachedProduct(redis, "catalog:slug:$slug") { repository.findBySlug(slug) } ?: throw ApiException(ErrorCode.NOT_FOUND, "Product not found.", 404)) }
            get("/{productId}") { val id = call.parameters.requireValue("productId"); call.respond(cachedProduct(redis, "catalog:product:$id") { repository.find(id, true) } ?: throw ApiException(ErrorCode.NOT_FOUND, "Product not found.", 404)) }
            post { val principal = call.requirePermission(verifier, "PRODUCT_CREATE"); val request = call.receive<ProductRequest>(); val sellerId = if (request.ownerType.uppercase() == "SELLER") principal.subject else request.sellerId ?: principal.subject; val input = request.input(sellerId); val product = repository.create(input, principal.subject, call.callId.orEmpty()); redis.delete("catalog:slug:${input.slug}"); call.respond(HttpStatusCode.Created, product) }
            patch("/{productId}") { val principal = call.requirePermission(verifier, "PRODUCT_UPDATE"); val request = call.receive<ProductRequest>(); val existing = repository.find(call.parameters.requireValue("productId"), false) ?: throw ApiException(ErrorCode.NOT_FOUND, "Product not found.", 404); val sellerId = if (request.ownerType.uppercase() == "SELLER") principal.subject else request.sellerId ?: existing.sellerId; val product = repository.update(existing.id, request.input(sellerId), principal.subject, call.callId.orEmpty()); redis.delete("catalog:product:${existing.id}", "catalog:slug:${existing.slug}", "catalog:slug:${product.slug}"); call.respond(product) }
            delete("/{productId}") { val principal = call.requirePermission(verifier, "PRODUCT_DELETE"); val id = call.parameters.requireValue("productId"); val existing = repository.find(id, false); repository.delete(id, principal.subject, call.callId.orEmpty()); redis.delete("catalog:product:$id", "catalog:slug:${existing?.slug}"); call.respond(Message("Product archived.")) }
            post("/{productId}/publish") { val principal = call.requirePermission(verifier, "PRODUCT_PUBLISH"); val id = call.parameters.requireValue("productId"); val product = repository.changeStatus(id, ProductStatus.ACTIVE, principal.subject, call.callId.orEmpty()); redis.delete("catalog:product:$id", "catalog:slug:${product.slug}"); call.respond(product) }
            post("/{productId}/unpublish") { val principal = call.requirePermission(verifier, "PRODUCT_UPDATE"); val id = call.parameters.requireValue("productId"); val product = repository.changeStatus(id, ProductStatus.INACTIVE, principal.subject, call.callId.orEmpty()); redis.delete("catalog:product:$id", "catalog:slug:${product.slug}"); call.respond(product) }
        }
        post("/api/v1/internal/products/{productId}/publish") { call.requireInternal(internalToken); val product = repository.changeStatus(call.parameters.requireValue("productId"), ProductStatus.ACTIVE, call.request.headers["X-Actor-Id"] ?: "admin-service", call.callId.orEmpty()); redis.delete("catalog:product:${product.id}", "catalog:slug:${product.slug}"); call.respond(product) }
    }
}

private class CatalogCacheAdapter(private val delegate: RedisCache) : CatalogCache {
    override fun get(key: String) = delegate.get(key)
    override fun put(key: String, value: String, ttl: Duration) = delegate.put(key, value, ttl)
    override fun delete(vararg keys: String) = delegate.delete(*keys)
}

@Serializable private data class Health(val status: String, val component: String)
@Serializable private data class Message(val message: String)
@Serializable private data class ProductPage(val items: List<Product>, val nextCursor: String?, val hasMore: Boolean)
@Serializable data class ProductRequest(val sellerId: String? = null, val ownerType: String = "SELLER", val brandId: String? = null, val categoryId: String, val name: String, val slug: String, val description: String, val shortDescription: String? = null, val skuReference: String? = null, val status: String = "DRAFT", val taxCategory: String? = null, val attributes: Map<String, String> = emptyMap(), val seoTitle: String? = null, val seoDescription: String? = null, val canonicalUrl: String? = null, val variants: List<VariantRequest> = emptyList(), val media: List<MediaRequest> = emptyList()) { fun input(seller: String) = ProductInput(seller, OwnerType.valueOf(ownerType.uppercase()), brandId, categoryId, name, slug, description, shortDescription, skuReference, ProductStatus.valueOf(status.uppercase()), taxCategory, attributes, seoTitle, seoDescription, canonicalUrl, variants.map { it.input() }, media.map { it.input() }) }
@Serializable data class VariantRequest(val sku: String, val barcode: String? = null, val attributes: Map<String, String> = emptyMap(), val priceReference: String? = null, val weightGrams: Int? = null, val dimensions: Map<String, String> = emptyMap(), val status: String = "ACTIVE") { fun input() = VariantInput(sku, barcode, attributes, priceReference, weightGrams, dimensions, VariantStatus.valueOf(status.uppercase())) }
@Serializable data class MediaRequest(val mediaId: String, val mediaType: String = "IMAGE", val url: String? = null, val sortOrder: Int = 0, val altText: String? = null) { fun input() = MediaInput(mediaId, mediaType, url, sortOrder, altText) }
private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private val catalogJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
private fun cachedProduct(redis: CatalogCache, key: String, loader: () -> Product?): Product? = redis.get(key)?.let { runCatching { catalogJson.decodeFromString<Product>(it) }.getOrNull() } ?: loader()?.also { redis.put(key, catalogJson.encodeToString(it), Duration.ofSeconds(30)) }
private fun cachedPage(redis: CatalogCache, key: String, loader: () -> ProductPage): ProductPage = redis.get(key)?.let { runCatching { catalogJson.decodeFromString<ProductPage>(it).takeIf { page -> page.items.isNotEmpty() || !page.hasMore } }.getOrNull() } ?: loader().also { redis.put(key, catalogJson.encodeToString(it), Duration.ofSeconds(20)) }
private fun ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val raw = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); val principal = verifier.verify(raw); if ("ADMIN" !in principal.roles && "SUPER_ADMIN" !in principal.roles && permission !in principal.permissions && "SELLER" !in principal.roles) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403); return principal }
private fun ApplicationCall.requireInternal(expected:String){if(expected.isBlank()||request.headers["X-Internal-Service-Token"]!=expected)throw ApiException(ErrorCode.FORBIDDEN,"Internal service authentication failed.",403)}
private fun Parameters.requireValue(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
