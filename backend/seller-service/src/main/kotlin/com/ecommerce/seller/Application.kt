package com.ecommerce.seller

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.kafka.KafkaConsumerWorker
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.requireAccess
import com.ecommerce.platform.security.requirePermission
import com.ecommerce.platform.service.DownstreamHttpClient
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.event.Level

interface SellerStore {
    fun byUser(user: String): SellerResponse?
    fun get(id: String): SellerResponse?
    fun create(owner: String, request: SellerApplication, actor: String, correlation: String): SellerResponse
    fun update(user: String, request: SellerProfileUpdate): SellerResponse
    fun orders(seller: String, limit: Int): List<SellerOrderItem>
    fun ledger(seller: String): List<LedgerEntry>
    fun transition(id: String, target: SellerStatus, actor: String, reason: String?, correlation: String): SellerResponse
    fun addLedger(seller: String, request: LedgerEntryRequest, actor: String): LedgerEntry
}

interface SellerProxy {
    fun request(baseUrl: String, method: String, path: String, bearer: String? = null, body: String? = null, requestId: String? = null, internalToken: String? = null, actorId: String? = null): com.ecommerce.platform.service.DownstreamResponse
}

data class SellerRouteConfig(val catalogUrl: String, val inventoryUrl: String, val promotionUrl: String, val analyticsUrl: String, val internalServiceToken: String)

fun Application.module() {
    val c=environment.config; val json=Json{ignoreUnknownKeys=true;encodeDefaults=true;explicitNulls=false}
    val db=ServiceDatabase(ServiceDatabaseConfig(c.required("seller.database.url"),c.required("seller.database.username"),c.required("seller.database.password"),c.required("seller.database.maximumPoolSize").toInt(),c.required("seller.database.connectionTimeoutMillis").toLong()),"classpath:db/migration")
    val redis=RedisCache(c.required("seller.redis.url")); val repo=SellerRepository(db.dataSource(),json); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val publisher=KafkaOutboxPublisher(repo,ServiceKafkaConfig(c.required("seller.kafka.bootstrapServers"),c.required("seller.kafka.topic"),c.required("seller.kafka.tenantId")),"seller-service");publisher.start(scope)
    val worker=if(c.required("seller.kafka.bootstrapServers").isNotBlank())KafkaConsumerWorker(c.required("seller.kafka.bootstrapServers"),c.required("seller.kafka.groupId"),c.required("seller.kafka.topics").split(',').map(String::trim).filter(String::isNotBlank),"seller-service",{repo.apply(it)},{event,error->repo.deadLetter(event,error)}).also{it.start(scope)}else null
    monitor.subscribe(ApplicationStopping){worker?.close();publisher.close();scope.cancel();redis.close();db.close()}
    val verifier=HmacJwtAccessVerifier(c.required("seller.jwt.issuer"),c.required("seller.jwt.audience"),parseKeys(c.required("seller.jwt.keys")));val http=DownstreamHttpClient()
    install(DefaultHeaders);install(CallId){header(HttpHeaders.XRequestId);generate{"req_${java.util.UUID.randomUUID()}"}};install(CallLogging){level=Level.INFO;mdc("requestId"){it.callId}};install(ContentNegotiation){json(json)}
    install(StatusPages){exception<ApiException>{call,e->call.respond(HttpStatusCode.fromValue(e.statusCode),ApiError(e.errorCode,e.message,call.callId.orEmpty(),e.fieldViolations,e.retryable))};exception<Throwable>{call,_->call.respond(HttpStatusCode.InternalServerError,ApiError(ErrorCode.INTERNAL_ERROR,"An unexpected error occurred.",call.callId.orEmpty()))}}
    install(CORS){allowHost("localhost:3000");allowHost("localhost:8080");allowHeader(HttpHeaders.ContentType);allowHeader(HttpHeaders.Authorization);allowHeader(HttpHeaders.XRequestId);allowHeader("If-Match");allowCredentials=true}
    routing {
        get("/health/live"){call.respond(Health("UP","seller-service"))};get("/health/ready"){if(runCatching{db.ping()&&redis.ping()}.getOrDefault(false))call.respond(Health("UP","seller-service"))else call.respond(HttpStatusCode.ServiceUnavailable,Health("DOWN","seller-service"))};get("/metrics"){call.respondText("seller_requests_total 1\nseller_consumer_lag_observed ${worker?.lagObserved?.get()?:0}\nseller_idor_denials_total 0\n",ContentType.Text.Plain)}
    }
    configureSellerRoutes(SellerRepositoryAdapter(repo), SellerProxyAdapter(http), verifier, SellerRouteConfig(c.required("seller.catalogUrl"), c.required("seller.inventoryUrl"), c.required("seller.promotionUrl"), c.required("seller.analyticsUrl"), c.required("seller.internalServiceToken")), json)
}

fun Application.configureSellerRoutes(repository: SellerStore, proxyClient: SellerProxy, verifier: HmacJwtAccessVerifier, config: SellerRouteConfig, json: Json) {
    fun token(call: io.ktor.server.application.ApplicationCall) = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401)
    fun seller(call: io.ktor.server.application.ApplicationCall) = repository.byUser(call.requireAccess(verifier).subject) ?: throw ApiException(ErrorCode.FORBIDDEN, "Seller account is not active for this user.", 403)
    suspend fun forward(call: io.ktor.server.application.ApplicationCall, response: com.ecommerce.platform.service.DownstreamResponse) { call.respondText(response.body, ContentType.parse(response.contentType), HttpStatusCode.fromValue(response.status)) }
    fun ownsProduct(call: io.ktor.server.application.ApplicationCall, productId: String, sellerId: String): String { val response = proxyClient.request(config.catalogUrl, "GET", "/api/v1/products/$productId", token(call), requestId = call.callId.orEmpty()); if (response.status !in 200..299) throw ApiException(ErrorCode.NOT_FOUND, "Product not found.", 404); val owner = runCatching { json.parseToJsonElement(response.body).jsonObject["sellerId"]?.jsonPrimitive?.content }.getOrNull(); if (owner != sellerId) throw ApiException(ErrorCode.FORBIDDEN, "You do not own this product.", 403); return response.body }
    routing {
        get("/api/v1/sellers/{sellerId}") { call.respond(repository.get(call.parameters["sellerId"]!!) ?: throw ApiException(ErrorCode.NOT_FOUND, "Seller not found.", 404)) }
        post("/api/v1/seller/applications") { val principal = call.requireAccess(verifier); call.respond(HttpStatusCode.Created, repository.create(principal.subject, call.receive(), principal.subject, call.callId.orEmpty())) }
        get("/api/v1/seller/profile") { call.respond(seller(call)) }; patch("/api/v1/seller/profile") { val principal = call.requireAccess(verifier); call.respond(repository.update(principal.subject, call.receive())) }
        get("/api/v1/seller/products") { val current = seller(call); forward(call, proxyClient.request(config.catalogUrl, "GET", "/api/v1/products?sellerId=${current.id}&${call.request.queryParameters.entries().joinToString("&") { (k, v) -> "$k=${v.firstOrNull().orEmpty()}" }}", token(call), requestId = call.callId.orEmpty())) }
        post("/api/v1/seller/products") { val current = seller(call); val raw = call.receiveText(); val body = runCatching { json.parseToJsonElement(raw).jsonObject.toMutableMap().apply { put("ownerType", kotlinx.serialization.json.JsonPrimitive("SELLER")); put("sellerId", kotlinx.serialization.json.JsonPrimitive(current.id)) }.let { json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(it)) } }.getOrElse { throw ApiException(ErrorCode.VALIDATION_ERROR, "Product JSON is invalid.", 400) }; forward(call, proxyClient.request(config.catalogUrl, "POST", "/api/v1/products", token(call), body, call.callId.orEmpty())) }
        patch("/api/v1/seller/products/{productId}") { val current = seller(call); val id = call.parameters["productId"]!!; ownsProduct(call, id, current.id); forward(call, proxyClient.request(config.catalogUrl, "PATCH", "/api/v1/products/$id", token(call), call.receiveText(), call.callId.orEmpty())) }
        get("/api/v1/seller/orders") { call.respond(repository.orders(seller(call).id, call.request.queryParameters["limit"]?.toIntOrNull() ?: 100)) }; get("/api/v1/seller/orders/{orderId}") { val current = seller(call); val items = repository.orders(current.id, 100).filter { it.orderId == call.parameters["orderId"] }; if (items.isEmpty()) throw ApiException(ErrorCode.NOT_FOUND, "Order not found.", 404); call.respond(items) }
        get("/api/v1/seller/inventory") { val current = seller(call); val variant = call.request.queryParameters["variantId"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "variantId is required.", 400); val product = call.request.queryParameters["productId"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "productId is required.", 400); ownsProduct(call, product, current.id); forward(call, proxyClient.request(config.inventoryUrl, "GET", "/api/v1/inventory/items/$variant", token(call), requestId = call.callId.orEmpty())) }
        get("/api/v1/seller/promotions") { val current = seller(call); forward(call, proxyClient.request(config.promotionUrl, "GET", "/api/v1/internal/promotions?sellerId=${current.id}&limit=${call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50}", internalToken = config.internalServiceToken, actorId = current.id, requestId = call.callId.orEmpty())) }; get("/api/v1/seller/analytics") { val current = seller(call); forward(call, proxyClient.request(config.analyticsUrl, "GET", "/api/v1/analytics/summary?sellerId=${current.id}", token(call), requestId = call.callId.orEmpty())) }
        get("/api/v1/seller/ledger") { call.respond(repository.ledger(seller(call).id)) }
        post("/api/v1/admin/sellers/{sellerId}/status") { val principal = call.requirePermission(verifier, "ADMIN_SELLER_UPDATE"); val request = call.receive<SellerStatusRequest>(); call.respond(repository.transition(call.parameters["sellerId"]!!, request.status, principal.subject, request.reason, call.callId.orEmpty())) }
        post("/api/v1/admin/sellers/{sellerId}/ledger") { val principal = call.requirePermission(verifier, "ADMIN_SELLER_UPDATE"); call.respond(repository.addLedger(call.parameters["sellerId"]!!, call.receive(), principal.subject)) }
    }
}

private class SellerRepositoryAdapter(private val delegate: SellerRepository) : SellerStore {
    override fun byUser(user: String) = delegate.byUser(user)
    override fun get(id: String) = delegate.get(id)
    override fun create(owner: String, request: SellerApplication, actor: String, correlation: String) = delegate.create(owner, request, actor, correlation)
    override fun update(user: String, request: SellerProfileUpdate) = delegate.update(user, request)
    override fun orders(seller: String, limit: Int) = delegate.orders(seller, limit)
    override fun ledger(seller: String) = delegate.ledger(seller)
    override fun transition(id: String, target: SellerStatus, actor: String, reason: String?, correlation: String) = delegate.transition(id, target, actor, reason, correlation)
    override fun addLedger(seller: String, request: LedgerEntryRequest, actor: String) = delegate.addLedger(seller, request, actor)
}

private class SellerProxyAdapter(private val delegate: DownstreamHttpClient) : SellerProxy {
    override fun request(baseUrl: String, method: String, path: String, bearer: String?, body: String?, requestId: String?, internalToken: String?, actorId: String?) = delegate.request(baseUrl, method, path, bearer, body, requestId, internalToken, actorId)
}

private fun ApplicationConfig.required(p:String)=property(p).getString()
private fun parseKeys(v:String)=v.split(',').associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}.filterValues{it.isNotBlank()}
@Serializable private data class Health(val status:String,val component:String)
