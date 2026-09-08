package com.ecommerce.gateway
import io.ktor.server.application.log

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.module(httpClient: HttpClient = HttpClient(CIO)) {
    monitor.subscribe(ApplicationStopped) { httpClient.close() }

    install(DefaultHeaders)
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { it.length in 8..128 }
        generate { "req_${java.util.UUID.randomUUID()}" }
    }
    install(CallLogging) {
        level = Level.INFO
        mdc("requestId") { it.callId }
        mdc("traceId") { it.request.header("traceparent").orEmpty() }
    }
    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ApiError(
                    code = cause.errorCode,
                    message = cause.message,
                    requestId = call.requestId(),
                    retryable = cause.retryable,
                    fieldViolations = cause.fieldViolations,
                ),
            )
        }
        exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause);
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    code = ErrorCode.INTERNAL_ERROR,
                    message = "An unexpected error occurred.",
                    requestId = call.requestId(),
                ),
            )
        }
    }
    install(CORS) {
        allowHost("localhost:3000")
        allowHost("localhost:3001")
        allowHost("localhost:3002")
        allowHost("localhost:8080")
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowHeader(HttpHeaders.XRequestId)
        allowHeader("Idempotency-Key")
        allowHeader("If-Match")
        allowHeader("X-Guest-Token")
        // Ktor's CORS plugin only allows GET/POST/HEAD by default -- every other proxied method
        // this gateway forwards (seller product edit/delete, admin updates, etc.) needs an
        // explicit allowMethod or the browser's preflight OPTIONS request is rejected with 403
        // before the real request is ever sent, regardless of what the downstream service allows.
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }
    routing {
        get("/health/live") {
            call.respond(HealthResponse(status = "UP", component = "api-gateway"))
        }
        get("/health/ready") {
            call.respond(HealthResponse(status = "UP", component = "api-gateway"))
        }
        get("/metrics") {
            call.respondText(
                "# HELP gateway_up Gateway process health\n# TYPE gateway_up gauge\ngateway_up 1\n",
                contentType = io.ktor.http.ContentType.Text.Plain,
            )
        }
        route("/{...}") {
            handle {
                proxyToService(call, httpClient)
            }
        }
    }
}

@Serializable
internal data class HealthResponse(
    val status: String,
    val component: String,
)

private fun ApplicationCall.requestId(): String = callId ?: request.header(HttpHeaders.XRequestId).orEmpty()
