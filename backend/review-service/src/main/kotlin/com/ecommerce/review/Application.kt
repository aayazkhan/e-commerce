package com.ecommerce.review

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.kafka.EventEnvelope
import com.ecommerce.platform.kafka.KafkaConsumerWorker
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.VerifiedAccessToken
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
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
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

interface ReviewStore {
    fun create(userId: String, request: ReviewRequest): ReviewResponse
    fun list(target: ReviewTarget, targetId: String, cursor: Int, limit: Int): ReviewPage
    fun aggregate(target: ReviewTarget, targetId: String): RatingAggregate
    fun vote(userId: String, reviewId: String)
    fun report(userId: String, reviewId: String, request: ReportRequest)
    fun moderate(reviewId: String, request: ModerationRequest, actor: String): ReviewResponse
}

private class ReviewStoreAdapter(private val repository: ReviewRepository) : ReviewStore {
    override fun create(userId: String, request: ReviewRequest) = repository.create(userId, request)
    override fun list(target: ReviewTarget, targetId: String, cursor: Int, limit: Int) = repository.list(target, targetId, cursor, limit)
    override fun aggregate(target: ReviewTarget, targetId: String) = repository.aggregate(target, targetId)
    override fun vote(userId: String, reviewId: String) = repository.vote(userId, reviewId)
    override fun report(userId: String, reviewId: String, request: ReportRequest) = repository.report(userId, reviewId, request)
    override fun moderate(reviewId: String, request: ModerationRequest, actor: String) = repository.moderate(reviewId, request, actor)
}

fun Application.module() {
    val c = environment.config
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val db = ServiceDatabase(ServiceDatabaseConfig(c.required("review.database.url"), c.required("review.database.username"), c.required("review.database.password"), c.required("review.database.maximumPoolSize").toInt(), c.required("review.database.connectionTimeoutMillis").toLong()), "classpath:db/migration")
    val repo = ReviewRepository(db.dataSource(), json, c.required("review.moderation.autoPublish").toBoolean())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val worker = if (c.required("review.kafka.bootstrapServers").isNotBlank()) KafkaConsumerWorker(c.required("review.kafka.bootstrapServers"), c.required("review.kafka.groupId"), c.required("review.kafka.topics").split(',').map(String::trim).filter(String::isNotBlank), "review-service", { repo.accept(it) }, { e, x -> repo.dlq(e, x) }).also { it.start(scope) } else null
    monitor.subscribe(ApplicationStopping) { worker?.close(); scope.cancel(); db.close() }
    val verifier = HmacJwtAccessVerifier(c.required("review.jwt.issuer"), c.required("review.jwt.audience"), parseKeys(c.required("review.jwt.keys")))
    install(DefaultHeaders)
    install(CallId) { header(HttpHeaders.XRequestId); generate { "req_${java.util.UUID.randomUUID()}" } }
    install(CallLogging) { level = Level.INFO; mdc("requestId") { it.callId } }
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.statusCode), ApiError(e.errorCode, e.message, call.callId.orEmpty(), e.fieldViolations, e.retryable)) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", call.callId.orEmpty())) }
    }
    install(CORS) { allowHost("localhost:3000"); allowHost("localhost:8080"); allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowCredentials = true }
    routing {
        get("/health/live") { call.respond(Health("UP", "review-service")) }
        get("/health/ready") { if (runCatching { db.ping() }.getOrDefault(false)) call.respond(Health("UP", "review-service")) else call.respond(HttpStatusCode.ServiceUnavailable, Health("DOWN", "review-service")) }
        get("/metrics") { call.respondText("review_consumer_lag_observed ${worker?.lagObserved?.get() ?: 0}\nreview_consumer_failures ${worker?.failuresObserved?.get() ?: 0}\n", ContentType.Text.Plain) }
    }
    configureReviewRoutes(ReviewStoreAdapter(repo), verifier)
}

fun Application.configureReviewRoutes(store: ReviewStore, verifier: HmacJwtAccessVerifier) {
    routing {
        post("/api/v1/reviews") { val principal = call.user(verifier); call.respond(HttpStatusCode.Created, store.create(principal.subject, call.receive())) }
        get("/api/v1/reviews/products/{id}") { call.respond(store.list(ReviewTarget.PRODUCT, call.parameters["id"]!!, call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0, call.request.queryParameters["limit"]?.toIntOrNull() ?: 20)) }
        get("/api/v1/reviews/sellers/{id}") { call.respond(store.list(ReviewTarget.SELLER, call.parameters["id"]!!, call.request.queryParameters["cursor"]?.toIntOrNull() ?: 0, call.request.queryParameters["limit"]?.toIntOrNull() ?: 20)) }
        get("/api/v1/reviews/{type}/{id}/aggregate") { call.respond(store.aggregate(ReviewTarget.valueOf(call.parameters["type"]!!.uppercase()), call.parameters["id"]!!)) }
        post("/api/v1/reviews/{id}/helpful") { val principal = call.user(verifier); store.vote(principal.subject, call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
        post("/api/v1/reviews/{id}/report") { val principal = call.user(verifier); store.report(principal.subject, call.parameters["id"]!!, call.receive()); call.respond(HttpStatusCode.Accepted) }
        patch("/api/v1/admin/reviews/{id}/moderation") { val principal = call.permission(verifier, "ADMIN_REVIEW_MODERATE"); call.respond(store.moderate(call.parameters["id"]!!, call.receive(), principal.subject)) }
    }
}

private fun ApplicationConfig.required(path: String) = property(path).getString()
private fun parseKeys(value: String) = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterValues { it.isNotBlank() }
private fun io.ktor.server.application.ApplicationCall.user(verifier: HmacJwtAccessVerifier): VerifiedAccessToken { val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401); return verifier.verify(token) }
private fun io.ktor.server.application.ApplicationCall.permission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken { val principal = user(verifier); if (!principal.isPrivileged() && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "Permission required.", 403); return principal }
private fun VerifiedAccessToken.isPrivileged() = roles.any { it in setOf("ADMIN", "SUPER_ADMIN", "SUPPORT") }
@Serializable private data class Health(val status: String, val component: String)
