package com.ecommerce.wishlist

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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.module() {
    val config = environment.config
    val database = ServiceDatabase(ServiceDatabaseConfig(config.required("wishlist.database.url"), config.required("wishlist.database.username"), config.required("wishlist.database.password"), config.required("wishlist.database.maximumPoolSize").toInt(), config.required("wishlist.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val repository = WishlistRepository(database.dataSource())
    val enricher = WishlistEnricher(config.required("wishlist.pricingBaseUrl"), config.required("wishlist.inventoryBaseUrl"))
    val verifier = HmacJwtAccessVerifier(config.required("wishlist.jwt.issuer"), config.required("wishlist.jwt.audience"), parseKeys(config.required("wishlist.jwt.keys")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val publisher = KafkaOutboxPublisher(repository, ServiceKafkaConfig(config.required("wishlist.kafka.bootstrapServers"), config.required("wishlist.kafka.topic"), config.required("wishlist.kafka.tenantId")), "wishlist-service")
    publisher.start(scope)
    monitor.subscribe(ApplicationStopping) { publisher.close(); scope.cancel(); database.close() }

    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "wishlist-service")) }
        get("/health/ready") { if (runCatching { database.ping() }.getOrDefault(false)) call.respond(Health("UP", "wishlist-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "wishlist-service")) }
        get("/metrics") { call.respondText("# TYPE wishlist_requests_total counter\nwishlist_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureWishlistRoutes(repository, enricher, verifier)
}

fun Application.configureWishlistRoutes(repository: WishlistRouteStore, enricher: WishlistRouteEnricher, verifier: HmacJwtAccessVerifier) {
    routing {
        get("/api/v1/wishlist") { val principal = call.requireAuth(verifier); val page = repository.list(principal.subject, call.request.queryParameters["cursor"], call.request.queryParameters["limit"]?.toIntOrNull() ?: 20); call.respond(WishlistPage(page.records.map { enricher.enrich(it, call.currency()) }, page.nextCursor, page.hasMore)) }
        post("/api/v1/wishlist") { val principal = call.requireAuth(verifier); val request = call.receive<WishlistRequest>(); val record = repository.add(principal.subject, request.productId, request.variantId, call.callId.orEmpty()); call.respond(HttpStatusCode.Created, enricher.enrich(record, call.currency())) }
        post("/api/v1/wishlist/items") { val principal = call.requireAuth(verifier); val request = call.receive<WishlistRequest>(); val record = repository.add(principal.subject, request.productId, request.variantId, call.callId.orEmpty()); call.respond(HttpStatusCode.Created, enricher.enrich(record, call.currency())) }
        delete("/api/v1/wishlist/{variantId}") { val principal = call.requireAuth(verifier); repository.remove(principal.subject, call.parameters.required("variantId"), call.callId.orEmpty()); call.respond(Message("Wishlist item removed.")) }
        delete("/api/v1/wishlist/items/{variantId}") { val principal = call.requireAuth(verifier); repository.remove(principal.subject, call.parameters.required("variantId"), call.callId.orEmpty()); call.respond(Message("Wishlist item removed.")) }
        delete("/api/v1/wishlist") { val principal = call.requireAuth(verifier); call.respond(Message("${repository.clear(principal.subject, call.callId.orEmpty())} wishlist items removed.")) }
    }
}

@Serializable private data class Health(val status: String, val component: String)
@Serializable private data class Message(val message: String)
@Serializable private data class WishlistRequest(val productId: String, val variantId: String)
private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.requireAuth(verifier: HmacJwtAccessVerifier): VerifiedAccessToken = verifier.verify(request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401))
private fun io.ktor.server.application.ApplicationCall.currency() = request.queryParameters["currency"]?.uppercase() ?: "INR"
private fun io.ktor.http.Parameters.required(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
