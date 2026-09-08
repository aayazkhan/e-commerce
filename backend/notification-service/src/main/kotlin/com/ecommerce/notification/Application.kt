package com.ecommerce.notification
import io.ktor.server.application.log

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
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface NotificationProvider { val name:String; fun send(delivery:Delivery):String? }
open class HttpNotificationProvider(override val name:String, private val endpoint:String, private val apiKey:String):NotificationProvider {
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    override fun send(delivery:Delivery):String? { if(endpoint.isBlank()) throw IllegalStateException("$name provider is not configured"); val request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer $apiKey").header("Content-Type","application/json").header("Idempotency-Key",delivery.id).POST(HttpRequest.BodyPublishers.ofString("{\"recipient\":\"${delivery.userId}\",\"subject\":\"${delivery.subject.replace("\"","\\\"")}\",\"body\":\"${delivery.body.replace("\"","\\\"")}\"}")).build();val response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode() !in 200..299)throw IllegalStateException("$name provider returned ${response.statusCode()}");return response.headers().firstValue("X-Message-Id").orElse(delivery.id)}
}
class FcmPushProvider(endpoint:String,key:String):HttpNotificationProvider("fcm",endpoint,key)
class ApnsPushProvider(endpoint:String,key:String):HttpNotificationProvider("apns",endpoint,key)

interface NotificationStore {
    fun getPreferences(userId: String): PreferenceRequest
    fun preferences(userId: String, preferences: PreferenceRequest): Int
    fun device(userId: String, device: DeviceRequest): Int
    fun listInApp(userId: String, limit: Int): List<InAppResponse>
    fun markRead(userId: String, id: String): Int
    fun template(template: TemplateRequest): Int
    fun updateWebhook(messageId: String, status: String): Int
}

fun Application.module() {
    val c=environment.config; val json=Json{ignoreUnknownKeys=true;encodeDefaults=true;explicitNulls=false}
    val db=ServiceDatabase(ServiceDatabaseConfig(c.required("notification.database.url"),c.required("notification.database.username"),c.required("notification.database.password"),c.required("notification.database.maximumPoolSize").toInt(),c.required("notification.database.connectionTimeoutMillis").toLong()),"classpath:db/migration")
    val repo=NotificationRepository(db.dataSource(),json); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val providers=mapOf("email" to HttpNotificationProvider("email",c.required("notification.providers.email.endpoint"),c.required("notification.providers.email.apiKey")),"sms" to HttpNotificationProvider("sms",c.required("notification.providers.sms.endpoint"),c.required("notification.providers.sms.apiKey")),"fcm" to FcmPushProvider(c.required("notification.providers.fcm.endpoint"),c.required("notification.providers.fcm.apiKey")),"apns" to ApnsPushProvider(c.required("notification.providers.apns.endpoint"),c.required("notification.providers.apns.apiKey")))
    scope.launch(Dispatchers.IO) { while (isActive) { repo.claimDue()?.let { delivery: Delivery ->
        processNotificationDelivery(delivery, providers, { item -> repo.inApp(item.id, item.userId, item.subject, item.body) }, repo::sent, repo::failed)
    }; delay(250) } }
    val worker=if(c.required("notification.kafka.bootstrapServers").isNotBlank())KafkaConsumerWorker(c.required("notification.kafka.bootstrapServers"),c.required("notification.kafka.groupId"),c.required("notification.kafka.topics").split(',').map(String::trim).filter(String::isNotBlank),"notification-service",{repo.accept(it,parseChannels(c.required("notification.defaultChannels")))},{event,error->repo.dlq(event,error)}).also{it.start(scope)}else null
    monitor.subscribe(ApplicationStopping){worker?.close();scope.cancel();db.close()}
    val verifier=HmacJwtAccessVerifier(c.required("notification.jwt.issuer"),c.required("notification.jwt.audience"),parseKeys(c.required("notification.jwt.keys")))
    install(DefaultHeaders);install(CallId){header(HttpHeaders.XRequestId);generate{"req_${java.util.UUID.randomUUID()}"}};install(CallLogging){level=Level.INFO;mdc("requestId"){it.callId}};install(ContentNegotiation){json(json)}
    install(StatusPages){exception<ApiException>{call,e->call.respond(HttpStatusCode.fromValue(e.statusCode),ApiError(e.errorCode,e.message,call.callId.orEmpty(),e.fieldViolations,e.retryable))};exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause);call.respond(HttpStatusCode.InternalServerError,ApiError(ErrorCode.INTERNAL_ERROR,"An unexpected error occurred.",call.callId.orEmpty()))}}
    install(CORS){allowHost("localhost:3000");allowHost("localhost:8080");allowHeader(HttpHeaders.ContentType);allowHeader(HttpHeaders.Authorization);allowHeader(HttpHeaders.XRequestId);allowCredentials=true}
    routing {
        get("/health/live"){call.respond(Health("UP","notification-service"))};get("/health/ready"){if(runCatching{db.ping()}.getOrDefault(false))call.respond(Health("UP","notification-service"))else call.respond(HttpStatusCode.ServiceUnavailable,Health("DOWN","notification-service"))};get("/metrics"){call.respondText("# HELP notification_consumer_lag_observed Kafka records behind the consumer\nnotification_consumer_lag_observed ${worker?.lagObserved?.get()?:0}\nnotification_consumer_failures ${worker?.failuresObserved?.get()?:0}\n",ContentType.Text.Plain)}
    }
    configureNotificationRoutes(repo, verifier)
}

internal fun processNotificationDelivery(
    delivery: Delivery,
    providers: Map<String, NotificationProvider>,
    inApp: (Delivery) -> Unit,
    sent: (String, String, String?) -> Unit,
    failed: (Delivery, Throwable) -> Unit,
) {
    if (delivery.channel == NotificationChannel.IN_APP) {
        runCatching { inApp(delivery) }
            .onSuccess { sent(delivery.id, "in-app", null) }
            .onFailure { failed(delivery, it) }
    } else {
        runCatching {
            val providerName = when (delivery.channel) {
                NotificationChannel.EMAIL -> "email"
                NotificationChannel.SMS -> "sms"
                NotificationChannel.PUSH -> if (delivery.platform.uppercase() in setOf("IOS", "APNS")) "apns" else "fcm"
                else -> "email"
            }
            val provider = providers[providerName] ?: error("Provider is not configured for $providerName")
            sent(delivery.id, provider.name, provider.send(delivery))
        }.onFailure { failed(delivery, it) }
    }
}

fun Application.configureNotificationRoutes(store: NotificationStore, verifier: HmacJwtAccessVerifier) {
    routing {
        get("/api/v1/notifications/preferences") { val u = call.user(verifier); call.respond(store.getPreferences(u.subject)) }
        put("/api/v1/notifications/preferences") { val u = call.user(verifier); val p = call.receive<PreferenceRequest>(); store.preferences(u.subject, p); call.respond(p) }
        post("/api/v1/notifications/devices") { val u = call.user(verifier); store.device(u.subject, call.receive()); call.respond(HttpStatusCode.Created) }
        get("/api/v1/notifications/in-app") { val u = call.user(verifier); call.respond(store.listInApp(u.subject, call.request.queryParameters["limit"]?.toIntOrNull() ?: 50)) }
        patch("/api/v1/notifications/in-app/{id}/read") { val u = call.user(verifier); store.markRead(u.subject, call.parameters["id"] ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Notification id is required.", 400)); call.respond(HttpStatusCode.NoContent) }
        post("/api/v1/providers/webhook") { val body = call.receive<WebhookRequest>(); store.updateWebhook(body.providerMessageId, body.status); call.respond(HttpStatusCode.Accepted) }
        put("/api/v1/admin/notification-templates") { call.requirePermission(verifier, "ADMIN_NOTIFICATION_TEMPLATE"); store.template(call.receive()); call.respond(HttpStatusCode.NoContent) }
    }
}

private fun parseChannels(v:String)=v.split(',').mapNotNull{runCatching{NotificationChannel.valueOf(it.trim().uppercase())}.getOrNull()}.toSet().ifEmpty{setOf(NotificationChannel.IN_APP)}
private fun ApplicationConfig.required(path:String)=property(path).getString()
private fun parseKeys(v:String)=v.split(',').associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}.filterValues{it.isNotBlank()}
private fun io.ktor.server.application.ApplicationCall.user(v:HmacJwtAccessVerifier):VerifiedAccessToken{val t=request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?:throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED,"Authentication is required.",401);return v.verify(t)}
private fun io.ktor.server.application.ApplicationCall.requirePermission(v:HmacJwtAccessVerifier,p:String){val u=user(v);if(!u.isPrivileged()&&p !in u.permissions)throw ApiException(ErrorCode.FORBIDDEN,"Permission required.",403)}
private fun VerifiedAccessToken.isPrivileged()=roles.any{it in setOf("ADMIN","SUPER_ADMIN","SUPPORT")}
@kotlinx.serialization.Serializable private data class Health(val status:String,val component:String)
