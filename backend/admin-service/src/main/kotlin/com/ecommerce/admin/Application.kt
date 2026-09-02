package com.ecommerce.admin

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.requirePermission
import com.ecommerce.platform.service.DownstreamHttpClient
import com.ecommerce.platform.service.ServiceDatabase
import com.ecommerce.platform.service.ServiceDatabaseConfig
import com.ecommerce.platform.service.KafkaOutboxPublisher
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

interface AdminStore {
    fun createJob(type: String, actor: String, request: BulkJobRequest, correlation: String): AdminJobResponse
    fun get(id: String): AdminJobResponse?
}

interface AdminProxy {
    fun request(baseUrl: String, method: String, path: String, bearer: String? = null, body: String? = null, requestId: String? = null, internalToken: String? = null, actorId: String? = null): com.ecommerce.platform.service.DownstreamResponse
}

data class AdminRouteConfig(val analyticsUrl: String, val identityUrl: String, val sellerUrl: String, val catalogUrl: String, val orderUrl: String, val auditUrl: String, val cmsUrl: String, val flagsUrl: String)

fun Application.module(){
    val c=environment.config;val json=Json{ignoreUnknownKeys=true;encodeDefaults=true;explicitNulls=false};val db=ServiceDatabase(ServiceDatabaseConfig(c.required("admin.database.url"),c.required("admin.database.username"),c.required("admin.database.password"),c.required("admin.database.maximumPoolSize").toInt(),c.required("admin.database.connectionTimeoutMillis").toLong()),"classpath:db/migration");val repo=AdminRepository(db.dataSource(),json);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val publisher=KafkaOutboxPublisher(repo,ServiceKafkaConfig(c.required("admin.kafka.bootstrapServers"),c.required("admin.kafka.topic"),c.required("admin.kafka.tenantId")),"admin-service");publisher.start(scope);val http=DownstreamHttpClient();scope.launch{while(isActive){repo.claimItem()?.let{item->val execution=executeAdminJob(item,c.required("admin.catalogUrl"),c.required("admin.internalServiceToken")){baseUrl,method,path,internalToken,actorId->http.request(baseUrl,method,path,internalToken=internalToken,actorId=actorId)};repo.complete(item,execution.success,execution.error)};delay(250)}};monitor.subscribe(ApplicationStopping){publisher.close();scope.cancel();db.close()};val verifier=HmacJwtAccessVerifier(c.required("admin.jwt.issuer"),c.required("admin.jwt.audience"),parseKeys(c.required("admin.jwt.keys")));install(DefaultHeaders);install(CallId){header(HttpHeaders.XRequestId);generate{"req_${java.util.UUID.randomUUID()}"}};install(CallLogging){level=Level.INFO;mdc("requestId"){it.callId}};install(ContentNegotiation){json(json)};install(StatusPages){exception<ApiException>{call,e->call.respond(HttpStatusCode.fromValue(e.statusCode),ApiError(e.errorCode,e.message,call.callId.orEmpty(),e.fieldViolations,e.retryable))};exception<Throwable>{call,_->call.respond(HttpStatusCode.InternalServerError,ApiError(ErrorCode.INTERNAL_ERROR,"An unexpected error occurred.",call.callId.orEmpty()))}};install(CORS){allowHost("localhost:3000");allowHost("localhost:8080");allowHeader(HttpHeaders.ContentType);allowHeader(HttpHeaders.Authorization);allowHeader(HttpHeaders.XRequestId);allowCredentials=true};
    routing{
        get("/health/live"){call.respond(Health("UP","admin-service"))};get("/health/ready"){if(runCatching{db.ping()}.getOrDefault(false))call.respond(Health("UP","admin-service"))else call.respond(HttpStatusCode.ServiceUnavailable,Health("DOWN","admin-service"))};get("/metrics"){call.respondText("admin_requests_total 1\nadmin_bulk_jobs_total 1\n",ContentType.Text.Plain)}
    }
    configureAdminRoutes(AdminRepositoryAdapter(repo), AdminProxyAdapter(http), verifier, AdminRouteConfig(c.required("admin.analyticsUrl"), c.required("admin.identityUrl"), c.required("admin.sellerUrl"), c.required("admin.catalogUrl"), c.required("admin.orderUrl"), c.required("admin.auditUrl"), c.required("admin.cmsUrl"), c.required("admin.flagsUrl")))
}

internal data class AdminJobExecution(val success: Boolean, val error: String?)

internal fun executeAdminJob(
    item: JobItem,
    catalogUrl: String,
    internalToken: String,
    request: (baseUrl: String, method: String, path: String, internalToken: String, actorId: String) -> com.ecommerce.platform.service.DownstreamResponse,
): AdminJobExecution {
    val response = if (item.jobType == "PRODUCT_PUBLISH") {
        runCatching {
            request(
                catalogUrl,
                "POST",
                "/api/v1/internal/products/${item.itemKey}/publish",
                internalToken,
                item.actorId,
            )
        }.getOrElse { com.ecommerce.platform.service.DownstreamResponse(599, it.message ?: "request failed") }
    } else {
        com.ecommerce.platform.service.DownstreamResponse(400, "No executor configured")
    }
    val success = response.status in 200..299
    return AdminJobExecution(success, if (success) null else response.body.take(500))
}

fun Application.configureAdminRoutes(repository: AdminStore, proxyClient: AdminProxy, verifier: HmacJwtAccessVerifier, config: AdminRouteConfig) {
    fun raw(call: io.ktor.server.application.ApplicationCall) = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim() ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401)
    suspend fun forward(call: io.ktor.server.application.ApplicationCall, response: com.ecommerce.platform.service.DownstreamResponse) { call.respondText(response.body, ContentType.parse(response.contentType), HttpStatusCode.fromValue(response.status)) }
    suspend fun proxy(call: io.ktor.server.application.ApplicationCall, permission: String, base: String, path: String, method: String = "GET", body: String? = null) { call.requirePermission(verifier, permission); forward(call, proxyClient.request(base, method, path, raw(call), body, call.callId.orEmpty())) }
    routing {
        get("/api/v1/admin/dashboard") { proxy(call, "ADMIN_ANALYTICS_READ", config.analyticsUrl, "/api/v1/analytics/summary") }; get("/api/v1/admin/dashboard/sales") { proxy(call, "ADMIN_ANALYTICS_READ", config.analyticsUrl, "/api/v1/analytics/summary") }; get("/api/v1/admin/dashboard/orders") { proxy(call, "ADMIN_ORDER_READ", config.analyticsUrl, "/api/v1/analytics/summary") }; get("/api/v1/admin/dashboard/customers") { proxy(call, "ADMIN_ANALYTICS_READ", config.analyticsUrl, "/api/v1/analytics/summary") }; get("/api/v1/admin/dashboard/products") { proxy(call, "ADMIN_ANALYTICS_READ", config.analyticsUrl, "/api/v1/analytics/summary") }; get("/api/v1/admin/dashboard/inventory") { proxy(call, "ADMIN_INVENTORY_READ", config.analyticsUrl, "/api/v1/analytics/summary") }; get("/api/v1/admin/dashboard/payments") { proxy(call, "ADMIN_PAYMENT_READ", config.analyticsUrl, "/api/v1/analytics/summary") }; get("/api/v1/admin/dashboard/refunds") { proxy(call, "ADMIN_REFUND_READ", config.analyticsUrl, "/api/v1/analytics/summary") }
        get("/api/v1/admin/users") { proxy(call, "ADMIN_USER_READ", config.identityUrl, "/api/v1/admin/users") }; get("/api/v1/admin/users/{id}") { proxy(call, "ADMIN_USER_READ", config.identityUrl, "/api/v1/admin/users/${call.parameters["id"]}") }; patch("/api/v1/admin/users/{id}/status") { proxy(call, "ADMIN_USER_UPDATE", config.identityUrl, "/api/v1/admin/users/${call.parameters["id"]}/status", "PATCH", call.receiveText()) }; post("/api/v1/admin/users/{id}/suspend") { proxy(call, "ADMIN_USER_UPDATE", config.identityUrl, "/api/v1/admin/users/${call.parameters["id"]}/suspend", "POST") }; post("/api/v1/admin/users/{id}/restore") { proxy(call, "ADMIN_USER_UPDATE", config.identityUrl, "/api/v1/admin/users/${call.parameters["id"]}/restore", "POST") }
        get("/api/v1/admin/sellers") { proxy(call, "ADMIN_SELLER_READ", config.sellerUrl, "/api/v1/admin/sellers") }; post("/api/v1/admin/sellers/{id}/status") { proxy(call, "ADMIN_SELLER_UPDATE", config.sellerUrl, "/api/v1/admin/sellers/${call.parameters["id"]}/status", "POST", call.receiveText()) }
        get("/api/v1/admin/products") { proxy(call, "ADMIN_PRODUCT_READ", config.catalogUrl, "/api/v1/products?${call.request.queryParameters.entries().joinToString("&") { (k, v) -> "$k=${v.firstOrNull().orEmpty()}" }}") }; post("/api/v1/admin/products/{id}/publish") { proxy(call, "ADMIN_PRODUCT_PUBLISH", config.catalogUrl, "/api/v1/products/${call.parameters["id"]}/publish", "POST") }; post("/api/v1/admin/products/bulk-publish") { val principal = call.requirePermission(verifier, "ADMIN_PRODUCT_PUBLISH"); call.respond(HttpStatusCode.Accepted, repository.createJob("PRODUCT_PUBLISH", principal.subject, call.receive(), call.callId.orEmpty())) }
        get("/api/v1/admin/orders") { proxy(call, "ADMIN_ORDER_READ", config.orderUrl, "/api/v1/admin/orders") }; post("/api/v1/admin/orders/{id}/status") { proxy(call, "ADMIN_ORDER_UPDATE", config.orderUrl, "/api/v1/admin/orders/${call.parameters["id"]}/status", "POST", call.receiveText()) }; get("/api/v1/admin/audit") { proxy(call, "ADMIN_AUDIT_READ", config.auditUrl, "/api/v1/admin/audit") }; get("/api/v1/admin/audit/{id}") { proxy(call, "ADMIN_AUDIT_READ", config.auditUrl, "/api/v1/admin/audit/${call.parameters["id"]}") }
        get("/api/v1/admin/cms/pages") { proxy(call, "ADMIN_CMS_READ", config.cmsUrl, "/api/v1/admin/cms/pages") }; get("/api/v1/admin/feature-flags") { proxy(call, "ADMIN_FEATURE_FLAG_READ", config.flagsUrl, "/api/v1/admin/feature-flags") }; get("/api/v1/admin/jobs/{id}") { call.requirePermission(verifier, "ADMIN_ANALYTICS_READ"); call.respond(repository.get(call.parameters["id"]!!) ?: throw ApiException(ErrorCode.NOT_FOUND, "Job not found.", 404)) }
    }
}

private class AdminRepositoryAdapter(private val delegate: AdminRepository) : AdminStore {
    override fun createJob(type: String, actor: String, request: BulkJobRequest, correlation: String) = delegate.createJob(type, actor, request, correlation)
    override fun get(id: String) = delegate.get(id)
}

private class AdminProxyAdapter(private val delegate: DownstreamHttpClient) : AdminProxy {
    override fun request(baseUrl: String, method: String, path: String, bearer: String?, body: String?, requestId: String?, internalToken: String?, actorId: String?) = delegate.request(baseUrl, method, path, bearer, body, requestId, internalToken, actorId)
}

private fun ApplicationConfig.required(p:String)=property(p).getString();private fun parseKeys(v:String)=v.split(',').associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}.filterValues{it.isNotBlank()};@Serializable private data class Health(val status:String,val component:String)
