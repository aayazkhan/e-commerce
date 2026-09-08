package com.ecommerce.audit
import io.ktor.server.application.log

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.kafka.KafkaConsumerWorker
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.security.requirePermission
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

fun Application.module(){val c=environment.config;val json=Json{ignoreUnknownKeys=true;explicitNulls=false};val db=ServiceDatabase(ServiceDatabaseConfig(c.required("audit.database.url"),c.required("audit.database.username"),c.required("audit.database.password"),c.required("audit.database.maximumPoolSize").toInt(),c.required("audit.database.connectionTimeoutMillis").toLong()),"classpath:db/migration");val repo=AuditRepository(db.dataSource(),json);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val worker=if(c.required("audit.kafka.bootstrapServers").isNotBlank())KafkaConsumerWorker(c.required("audit.kafka.bootstrapServers"),c.required("audit.kafka.groupId"),c.required("audit.kafka.topics").split(',').map(String::trim).filter(String::isNotBlank),"audit-service",{repo.consume(it)},{e,x->repo.dlq(e,x)}).also{it.start(scope)}else null;monitor.subscribe(ApplicationStopping){worker?.close();scope.cancel();db.close()};val verifier=HmacJwtAccessVerifier(c.required("audit.jwt.issuer"),c.required("audit.jwt.audience"),parseKeys(c.required("audit.jwt.keys")));install(DefaultHeaders);install(CallId){header(HttpHeaders.XRequestId);generate{"req_${java.util.UUID.randomUUID()}"}};install(CallLogging){level=Level.INFO;mdc("requestId"){it.callId}};install(ContentNegotiation){json(json)};install(StatusPages){exception<ApiException>{call,e->call.respond(HttpStatusCode.fromValue(e.statusCode),ApiError(e.errorCode,e.message,call.callId.orEmpty(),e.fieldViolations,e.retryable))};exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause);call.respond(HttpStatusCode.InternalServerError,ApiError(ErrorCode.INTERNAL_ERROR,"An unexpected error occurred.",call.callId.orEmpty()))}};install(CORS){allowHost("localhost:3000");allowHost("localhost:8080");allowHeader(HttpHeaders.ContentType);allowHeader(HttpHeaders.Authorization);allowCredentials=true};routing{
get("/health/live"){call.respond(Health("UP","audit-service"))};get("/health/ready"){if(runCatching{db.ping()}.getOrDefault(false))call.respond(Health("UP","audit-service"))else call.respond(HttpStatusCode.ServiceUnavailable,Health("DOWN","audit-service"))};get("/metrics"){call.respondText("audit_events_total ${worker?.recordsObserved?.get()?:0}\naudit_consumer_lag ${worker?.lagObserved?.get()?:0}\n",ContentType.Text.Plain)}
get("/api/v1/admin/audit"){call.requirePermission(verifier,"ADMIN_AUDIT_READ");call.respond(repo.search(call.request.queryParameters["actor"],call.request.queryParameters["action"],call.request.queryParameters["resourceType"],call.request.queryParameters["resourceId"],call.request.queryParameters["sellerId"],call.request.queryParameters["traceId"],call.request.queryParameters["from"],call.request.queryParameters["to"],call.request.queryParameters["cursor"]?.toIntOrNull()?:0,call.request.queryParameters["limit"]?.toIntOrNull()?:50))};get("/api/v1/admin/audit/{auditId}"){call.requirePermission(verifier,"ADMIN_AUDIT_READ");call.respond(repo.get(call.parameters["auditId"]!!)?:throw ApiException(ErrorCode.NOT_FOUND,"Audit event not found.",404))};post("/api/v1/admin/audit"){val p=call.requirePermission(verifier,"ADMIN_AUDIT_READ");val request=call.receive<AuditRequest>();call.respond(repo.record(request.copy(actorId=p.subject,requestId=call.callId.orEmpty(),traceId=call.request.headers["traceparent"])))}}
}
private fun ApplicationConfig.required(p:String)=property(p).getString();private fun parseKeys(v:String)=v.split(',').associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}.filterValues{it.isNotBlank()};@Serializable private data class Health(val status:String,val component:String)
