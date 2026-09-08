package com.ecommerce.analytics
import io.ktor.server.application.log

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
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
import io.ktor.server.request.receive
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

interface AnalyticsStore {
    fun summary(from: String?, to: String?): MetricSummary
    fun replay(request: ReplayRequest): ReplayResponse
}

fun Application.module() {
    val config=environment.config; val json=Json{ignoreUnknownKeys=true;explicitNulls=false}
    val db=ServiceDatabase(ServiceDatabaseConfig(config.required("analytics.database.url"),config.required("analytics.database.username"),config.required("analytics.database.password"),config.required("analytics.database.maximumPoolSize").toInt(),config.required("analytics.database.connectionTimeoutMillis").toLong()),"classpath:db/migration")
    val repo=AnalyticsRepository(db.dataSource(),json); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val worker=if(config.required("analytics.kafka.bootstrapServers").isNotBlank())KafkaConsumerWorker(config.required("analytics.kafka.bootstrapServers"),config.required("analytics.kafka.groupId"),config.required("analytics.kafka.topics").split(',').map(String::trim).filter(String::isNotBlank),"analytics-service",{repo.accept(it)},{event,error->repo.dlq(event,error)}).also{it.start(scope)}else null
    monitor.subscribe(ApplicationStopping){worker?.close();scope.cancel();db.close()}
    val verifier=HmacJwtAccessVerifier(config.required("analytics.jwt.issuer"),config.required("analytics.jwt.audience"),parseKeys(config.required("analytics.jwt.keys")))
    install(DefaultHeaders); install(CallId){header(HttpHeaders.XRequestId);generate{"req_${java.util.UUID.randomUUID()}"}}; install(CallLogging){level=Level.INFO;mdc("requestId"){it.callId}}; install(ContentNegotiation){json(json)}
    install(StatusPages){exception<ApiException>{call,error->call.respond(HttpStatusCode.fromValue(error.statusCode),ApiError(error.errorCode,error.message,call.callId.orEmpty(),error.fieldViolations,error.retryable))};exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause);call.respond(HttpStatusCode.InternalServerError,ApiError(ErrorCode.INTERNAL_ERROR,"An unexpected error occurred.",call.callId.orEmpty()))}}
    install(CORS){allowHost("localhost:3000");allowHost("localhost:8080");allowHeader(HttpHeaders.ContentType);allowHeader(HttpHeaders.Authorization);allowCredentials=true}
    routing {
        get("/health/live"){call.respond(Health("UP","analytics-service"))}
        get("/health/ready"){if(runCatching{db.ping()}.getOrDefault(false))call.respond(Health("UP","analytics-service"))else call.respond(HttpStatusCode.ServiceUnavailable,Health("DOWN","analytics-service"))}
        get("/metrics"){call.respondText("analytics_events_observed ${worker?.recordsObserved?.get()?:0}\nanalytics_consumer_failures ${worker?.failuresObserved?.get()?:0}\nkafka_consumer_lag_observed 0\n",ContentType.Text.Plain)}
    }
    configureAnalyticsRoutes(repo, verifier)
}

fun Application.configureAnalyticsRoutes(store: AnalyticsStore, verifier: HmacJwtAccessVerifier) {
    routing {
        get("/api/v1/analytics/summary") {
            call.requirePermission(verifier, "ADMIN_ANALYTICS_READ")
            call.respond(store.summary(call.request.queryParameters["from"], call.request.queryParameters["to"]))
        }
        post("/api/v1/admin/analytics/replay") {
            call.requirePermission(verifier, "ADMIN_ANALYTICS_REPLAY")
            call.respond(store.replay(call.receive()))
        }
    }
}

internal fun ApplicationConfig.required(path:String)=property(path).getString()
internal fun parseKeys(value:String)=value.split(',').associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}.filterValues{it.isNotBlank()}
internal fun io.ktor.server.application.ApplicationCall.requirePermission(verifier:HmacJwtAccessVerifier,permission:String):VerifiedAccessToken { val token=request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()?:throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED,"Authentication is required.",401);val principal=verifier.verify(token);if(!principal.isPrivileged()&&permission !in principal.permissions)throw ApiException(ErrorCode.FORBIDDEN,"Permission required.",403);return principal }
internal fun VerifiedAccessToken.isPrivileged()=roles.any{it in setOf("ADMIN","SUPER_ADMIN","SUPPORT")}
@Serializable internal data class Health(val status:String,val component:String)
