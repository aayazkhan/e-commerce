package com.ecommerce.shipping

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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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

interface ShippingStore {
    fun quote(userId: String, request: ShippingQuoteRequest): ShippingQuote
    fun create(request: ShipmentCreateRequest, key: String, correlation: String): ShipmentResponse
    fun getOwned(userId: String, id: String): ShipmentResponse?
    fun getInternal(id: String): ShipmentResponse?
    fun tracking(request: TrackingWebhook, correlation: String): ShipmentResponse
}

interface ShippingWebhookVerifier {
    fun verifyWebhook(body: String, signature: String?): Boolean
}

private class ShippingStoreAdapter(private val repository: ShippingRepository) : ShippingStore {
    override fun quote(userId: String, request: ShippingQuoteRequest) = repository.quote(userId, request)
    override fun create(request: ShipmentCreateRequest, key: String, correlation: String) = repository.create(request, key, correlation)
    override fun getOwned(userId: String, id: String) = repository.getOwned(userId, id)
    override fun getInternal(id: String) = repository.getInternal(id)
    override fun tracking(request: TrackingWebhook, correlation: String) = repository.tracking(request, correlation)
}

fun Application.module() {
    val config = environment.config
    val db = ServiceDatabase(ServiceDatabaseConfig(config.required("shipping.database.url"), config.required("shipping.database.username"), config.required("shipping.database.password"), config.required("shipping.database.maximumPoolSize").toInt(), config.required("shipping.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val provider = HttpShippingProvider(config.required("shipping.provider.baseUrl"), config.required("shipping.provider.apiKey"), config.required("shipping.provider.webhookSecret"))
    val repo = ShippingRepository(db.dataSource(), provider)
    val verifier = HmacJwtAccessVerifier(config.required("shipping.jwt.issuer"), config.required("shipping.jwt.audience"), parseKeys(config.required("shipping.jwt.keys")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val publisher = KafkaOutboxPublisher(repo, ServiceKafkaConfig(config.required("shipping.kafka.bootstrapServers"), config.required("shipping.kafka.topic"), config.required("shipping.kafka.tenantId")), "shipping-service")
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
    install(CORS) { allowHost("localhost:3000"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader("Idempotency-Key"); allowHeader("X-Internal-Service-Token"); allowHeader("X-Provider-Signature"); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "shipping-service")) }
        get("/health/ready") { if (runCatching { db.ping() }.getOrDefault(false)) call.respond(Health("UP", "shipping-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "shipping-service")) }
        get("/metrics") { call.respondText("# TYPE shipping_requests_total counter\nshipping_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureShippingRoutes(ShippingStoreAdapter(repo), object : ShippingWebhookVerifier {
        override fun verifyWebhook(body: String, signature: String?) = provider.verifyWebhook(body, signature)
    }, verifier, config.required("shipping.internalToken"))
}

fun Application.configureShippingRoutes(store: ShippingStore, webhookVerifier: ShippingWebhookVerifier, verifier: HmacJwtAccessVerifier, internalToken: String) {
    routing {
        post("/api/v1/shipping/quotes") { val principal = call.requireUser(verifier); call.respond(store.quote(principal.subject, call.receive())) }
        post("/api/v1/internal/shipping/shipments") { call.requireInternal(internalToken); val key = call.request.header("Idempotency-Key") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required.", 400); call.respond(HttpStatusCode.Created, store.create(call.receive(), key, call.callId.orEmpty())) }
        get("/api/v1/shipping/shipments/{shipmentId}") { val principal = call.requireUser(verifier); call.respond(store.getOwned(principal.subject, call.parameters.required("shipmentId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Shipment not found.", 404)) }
        post("/api/v1/shipping/webhooks") { val body = call.receiveText(); if (!webhookVerifier.verifyWebhook(body, call.request.header("X-Provider-Signature"))) throw ApiException(ErrorCode.FORBIDDEN, "Webhook signature is invalid.", 403); call.respond(store.tracking(Json.decodeFromString(body), call.callId.orEmpty())) }
        get("/api/v1/internal/shipping/shipments/{shipmentId}") { call.requireInternal(internalToken); call.respond(store.getInternal(call.parameters.required("shipmentId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Shipment not found.", 404)) }
    }
}

@Serializable private data class Health(val status: String, val component: String)
private fun ApplicationConfig.required(path: String) = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun parseKeys(value: String) = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.requireUser(verifier: HmacJwtAccessVerifier): VerifiedAccessToken { val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); return verifier.verify(token) }
private fun io.ktor.server.application.ApplicationCall.requireInternal(expected: String) { if (expected.isBlank() || request.header("X-Internal-Service-Token") != expected) throw ApiException(ErrorCode.FORBIDDEN, "Internal service authentication failed.", 403) }
private fun io.ktor.http.Parameters.required(name: String) = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
