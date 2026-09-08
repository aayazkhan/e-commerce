package com.ecommerce.search

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.VerifiedAccessToken
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.event.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class CatalogSearchResponse(val statusCode: Int, val body: String)
interface CatalogSearchClient { fun get(path: String): CatalogSearchResponse }
data class SearchRouteConfig(val catalogBaseUrl: String)
interface SearchRouteStore {
    fun search(query: String, categoryId: String?, brandId: String?, minPrice: Long?, maxPrice: Long?, sort: String?, from: Int, size: Int): SearchResponse
    fun suggestions(query: String): SuggestionResponse
    fun filters(): FilterResponse
    fun newReindexIndex(): String
    fun indexInto(index: String, id: String, document: kotlinx.serialization.json.JsonObject)
    fun swapAlias(index: String)
    fun deleteProduct(id: String)
    fun indexProduct(id: String, document: kotlinx.serialization.json.JsonObject)
}

fun Application.module() {
    val config = environment.config; val database = ServiceDatabase(ServiceDatabaseConfig(config.required("search.database.url"), config.required("search.database.username"), config.required("search.database.password"), 10), "classpath:db/migration"); val client = SearchIndexClient(config.required("search.opensearch.url"), config.required("search.opensearch.alias"), config.optional("search.opensearch.username"), config.optional("search.opensearch.password"), config.required("search.opensearch.requestTimeoutMillis").toLong()); val catalogHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(); val catalogClient = JdkCatalogSearchClient(catalogHttpClient, config.required("search.catalogBaseUrl")); val repository = SearchRepository(database.dataSource()); val verifier = HmacJwtAccessVerifier(config.required("search.jwt.issuer"), config.required("search.jwt.audience"), parseKeys(config.required("search.jwt.keys"))); val consumer = SearchConsumer(SearchConsumerConfig(config.required("search.kafka.bootstrapServers"), config.required("search.kafka.topics").split(',').map(String::trim), config.required("search.kafka.groupId")), repository, client); client.ensureAlias(); consumer.start(); monitor.subscribe(ApplicationStopping) { consumer.close(); client.close(); database.close() }
    install(DefaultHeaders); install(CallId) { header(HttpHeaders.XRequestId); verify { it.length in 8..128 }; generate { "req_${java.util.UUID.randomUUID()}" } }; install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId }; mdc("traceId") { it.request.header("traceparent").orEmpty() } }; install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }) }; install(StatusPages) { exception<ApiException> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.statusCode), ApiError(cause.errorCode, cause.message, call.callId.orEmpty(), cause.fieldViolations, cause.retryable)) }; exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause); call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) } }; install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.XRequestId); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(mapOf("status" to "UP", "component" to "search-service")) }; get("/health/ready") { if (runCatching { database.ping() && client.ping() }.getOrDefault(false)) call.respond(mapOf("status" to "UP", "component" to "search-service")) else call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "DOWN", "component" to "search-service")) }; get("/metrics") { call.respondText("# TYPE search_requests_total counter\nsearch_requests_total 1\n", ContentType.Text.Plain) }
    }
    configureSearchRoutes(client, verifier, SearchRouteConfig(config.required("search.catalogBaseUrl")), catalogClient)
}

fun Application.configureSearchRoutes(store: SearchRouteStore, verifier: HmacJwtAccessVerifier, config: SearchRouteConfig, catalog: CatalogSearchClient) {
    routing {
        get("/api/v1/search") { val size = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 24; call.respond(store.search(call.request.queryParameters["q"].orEmpty(), call.request.queryParameters["categoryId"], call.request.queryParameters["brandId"], call.request.queryParameters["minPrice"]?.toLongOrNull(), call.request.queryParameters["maxPrice"]?.toLongOrNull(), call.request.queryParameters["sort"], call.request.queryParameters["from"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0, size)) }
        get("/api/v1/search/suggestions") { call.respond(store.suggestions(call.request.queryParameters["q"].orEmpty())) }
        get("/api/v1/search/filters") { call.respond(store.filters()) }
        post("/api/v1/admin/search/reindex") { call.requirePermission(verifier, "SEARCH_REINDEX"); val target = store.newReindexIndex(); var cursor: String? = null; do { val response = catalog.get("/api/v1/products?limit=100${cursor?.let { "&cursor=$it" } ?: ""}"); if (response.statusCode !in 200..299) throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Catalog unavailable for reindex.", 503, retryable = true); val page = Json.decodeFromString<ProductPage>(response.body); page.items.forEach { product -> store.indexInto(target, product["id"]!!.jsonPrimitive.content, product) }; cursor = page.nextCursor } while (cursor != null); store.swapAlias(target); call.respond(mapOf("status" to "SUCCEEDED", "index" to target)) }
        post("/api/v1/admin/search/reindex/{productId}") { call.requirePermission(verifier, "SEARCH_REINDEX"); val productId = call.parameters.requireValue("productId"); val response = catalog.get("/api/v1/products/$productId"); if (response.statusCode == 404) store.deleteProduct(productId) else if (response.statusCode in 200..299) { val product = Json.parseToJsonElement(response.body).jsonObject; store.indexProduct(productId, product) } else throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Catalog unavailable for reindex.", 503, retryable = true); call.respond(mapOf("status" to "SUCCEEDED", "productId" to productId)) }
    }
}

private class JdkCatalogSearchClient(private val client: HttpClient, private val baseUrl: String) : CatalogSearchClient {
    override fun get(path: String): CatalogSearchResponse { val response = client.send(HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString()); return CatalogSearchResponse(response.statusCode(), response.body()) }
}

@kotlinx.serialization.Serializable private data class ProductPage(val items: List<kotlinx.serialization.json.JsonObject>, val nextCursor: String?, val hasMore: Boolean)
private fun ApplicationConfig.required(path: String): String = property(path).getString().takeIf { it.isNotBlank() } ?: error("Missing configuration: $path")
private fun ApplicationConfig.optional(path: String): String? = runCatching { property(path).getString().takeIf { it.isNotBlank() } }.getOrNull()
private fun parseKeys(value: String): Map<String, String> = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val raw = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); val principal = verifier.verify(raw); if ("ADMIN" !in principal.roles && "SUPER_ADMIN" !in principal.roles && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403); return principal }
private fun Parameters.requireValue(name: String): String = this[name] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)
