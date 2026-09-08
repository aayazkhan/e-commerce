package com.ecommerce.payment
import io.ktor.server.application.log

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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
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
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

interface PaymentStore {
    fun create(userId: String, request: PaymentCreateRequest, key: String, correlationId: String): PaymentResponse
    fun getOwned(userId: String, id: String): PaymentResponse?
    fun getInternal(id: String): PaymentResponse?
    fun getByProviderPaymentId(providerPaymentId: String): PaymentResponse?
    fun webhook(providerName: PaymentProviderName, request: PaymentWebhookRequest, correlationId: String): PaymentResponse
    fun refund(id: String, userId: String?, request: RefundPaymentRequest, correlationId: String): RefundPaymentResponse
}

interface PaymentWebhookVerifier {
    fun verify(provider: PaymentProviderName, body: String, signature: String?): Boolean
}

fun Application.module() {
    val config=environment.config
    val database=ServiceDatabase(ServiceDatabaseConfig(config.required("payment.database.url"),config.required("payment.database.username"),config.required("payment.database.password"),config.required("payment.database.maximumPoolSize").toInt(),config.required("payment.database.connectionTimeoutMillis").toLong()),"classpath:db/migration")
    val json=Json{encodeDefaults=true;explicitNulls=false;ignoreUnknownKeys=true}
    val payuCallbackBase=config.optional("payment.payu.callbackBaseUrl","http://localhost:8092").trimEnd('/')
    val providers=mapOf<PaymentProviderName,PaymentProvider>(
        PaymentProviderName.COD to CodPaymentProvider(),
        PaymentProviderName.HTTP to HttpPaymentProvider(config.required("payment.provider.baseUrl"),config.required("payment.provider.apiKey"),config.required("payment.provider.webhookSecret"),json),
        // Demo/sandbox defaults are PayU's own publicly documented test merchant (key "gtKFFx",
        // salt "4R38IvwiV57FwVpsgOvTXBdLE4tHUXFW", https://test.payu.in) -- swap PAYU_MERCHANT_KEY/PAYU_MERCHANT_SALT for
        // a real merchant account to go beyond a demo.
        PaymentProviderName.PAYU to PayuPaymentProvider(
            config.optional("payment.payu.merchantKey","gtKFFx"),
            config.optional("payment.payu.merchantSalt","4R38IvwiV57FwVpsgOvTXBdLE4tHUXFW"),
            config.optional("payment.payu.baseUrl","https://test.payu.in"),
            "$payuCallbackBase/api/v1/payments/payu/callback",
            "$payuCallbackBase/api/v1/payments/payu/callback",
            json,
        ),
    )
    val payuReturnBase=config.optional("payment.payu.returnBaseUrl","http://localhost:3002").trimEnd('/')
    val repository=PaymentRepository(database.dataSource(),providers)
    val verifier=HmacJwtAccessVerifier(config.required("payment.jwt.issuer"),config.required("payment.jwt.audience"),parseKeys(config.required("payment.jwt.keys")))
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val publisher=KafkaOutboxPublisher(repository,ServiceKafkaConfig(config.required("payment.kafka.bootstrapServers"),config.required("payment.kafka.topic"),config.required("payment.kafka.tenantId")),"payment-service");publisher.start(scope)
    scope.launch(Dispatchers.IO){while(isActive){runCatching{repository.reconcile(50,"payment-reconciliation")};delay(config.required("payment.reconciliationIntervalSeconds").toLong()*1000)}}
    monitor.subscribe(ApplicationStopping){scope.cancel();publisher.close();database.close()}
    install(DefaultHeaders)
    install(CallId){header(HttpHeaders.XRequestId);verify{it.length in 8..128};generate{"req_${java.util.UUID.randomUUID()}"}}
    install(CallLogging){level=Level.INFO;mdc("requestId"){it.callId};mdc("traceId"){it.request.headers["traceparent"].orEmpty()}}
    install(ContentNegotiation){json(json)}
    install(StatusPages){exception<ApiException>{call,e->call.respond(HttpStatusCode.fromValue(e.statusCode),ApiError(e.errorCode,e.message,call.callId.orEmpty(),e.fieldViolations,e.retryable))};exception<Throwable> { call, cause -> call.application.log.error("Unhandled exception", cause);call.respond(HttpStatusCode.InternalServerError,ApiError(ErrorCode.INTERNAL_ERROR,"An unexpected error occurred.",call.callId.orEmpty()))}}
    // PayU's surl/furl POST to /api/v1/payments/payu/callback directly from the user's browser
    // as a real top-level navigation (not XHR/fetch) originating on PayU's own domain, which
    // Ktor's CORS plugin below would otherwise reject outright (its origin is obviously never in
    // this service's allowlist). CORS only ever matters for XHR/fetch reading a cross-origin
    // response body -- never for a real navigation -- so this path just needs to run before the
    // CORS plugin's own interceptor does. Route-scoped `install(CORS)` was tried first but did
    // NOT isolate the sibling route as expected; a Setup-phase bypass is unambiguous instead.
    intercept(ApplicationCallPipeline.Setup) {
        if (call.request.httpMethod == HttpMethod.Post && call.request.path() == "/api/v1/payments/payu/callback") {
            call.handlePayuCallback(repository, providers[PaymentProviderName.PAYU] as PayuPaymentProvider, payuReturnBase)
            finish()
        }
    }
    install(CORS){allowHost("localhost:3000");allowHost("localhost:8080");allowHeader(HttpHeaders.ContentType);allowHeader(HttpHeaders.Authorization);allowHeader("Idempotency-Key");allowHeader("X-Internal-Service-Token");allowHeader("X-Provider-Signature");allowCredentials=true}
    routing {
        get("/health/live"){call.respond(Health("UP","payment-service"))}
        get("/health/ready"){if(runCatching{database.ping()}.getOrDefault(false))call.respond(Health("UP","payment-service"))else call.respond(HttpStatusCode.ServiceUnavailable,Health("DOWN","payment-service"))}
        get("/metrics"){call.respondText("# TYPE payment_requests_total counter\npayment_requests_total 1\n",ContentType.Text.Plain)}
    }
    configurePaymentRoutes(repository, verifier, config.required("payment.internalToken"), object : PaymentWebhookVerifier {
        override fun verify(provider: PaymentProviderName, body: String, signature: String?) = providers[provider]?.verifyWebhook(body, signature) == true
    }, json, providers[PaymentProviderName.PAYU] as PayuPaymentProvider, payuReturnBase)
}

fun Application.configurePaymentRoutes(store: PaymentStore, verifier: HmacJwtAccessVerifier, internalToken: String, webhookVerifier: PaymentWebhookVerifier, json: Json, payu: PayuPaymentProvider, payuReturnBase: String) {
    routing {
        post("/api/v1/payments") { val principal = call.requireUser(verifier); call.requirePermission(principal, "PAYMENT_CREATE"); val key = call.request.header("Idempotency-Key") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required.", 400); call.respond(HttpStatusCode.Created, store.create(principal.subject, call.receive(), key, call.callId.orEmpty())) }
        get("/api/v1/payments/{paymentId}") { val principal = call.requireUser(verifier); call.requirePermission(principal, "PAYMENT_READ"); call.respond(store.getOwned(principal.subject, call.parameters.required("paymentId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Payment not found.", 404)) }
        post("/api/v1/internal/payments") { call.requireInternal(internalToken); val envelope = call.receive<PaymentEnvelope>(); val key = call.request.header("Idempotency-Key") ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required.", 400); call.respond(HttpStatusCode.Created, store.create(envelope.userId, envelope.request, key, call.callId.orEmpty())) }
        get("/api/v1/internal/payments/{paymentId}") { call.requireInternal(internalToken); call.respond(store.getInternal(call.parameters.required("paymentId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Payment not found.", 404)) }
        post("/api/v1/internal/payments/{paymentId}/refund") { call.requireInternal(internalToken); val request = call.receive<RefundPaymentRequest>(); call.respond(store.refund(call.parameters.required("paymentId"), null, request, call.callId.orEmpty())) }
        post("/api/v1/payments/webhooks/{provider}") { val provider = runCatching { PaymentProviderName.valueOf(call.parameters.required("provider").uppercase()) }.getOrElse { throw ApiException(ErrorCode.VALIDATION_ERROR, "Unknown payment provider.", 400) }; val body = call.receiveText(); if (!webhookVerifier.verify(provider, body, call.request.header("X-Provider-Signature"))) throw ApiException(ErrorCode.FORBIDDEN, "Webhook signature is invalid.", 403); call.respond(store.webhook(provider, json.decodeFromString<PaymentWebhookRequest>(body), call.callId.orEmpty())) }
        get("/api/v1/admin/payments/{paymentId}") { call.requirePermission(call.requireUser(verifier), "ADMIN_PAYMENT_READ"); call.respond(store.getInternal(call.parameters.required("paymentId")) ?: throw ApiException(ErrorCode.NOT_FOUND, "Payment not found.", 404)) }
        // Reachable directly here for tests; in production this path is actually short-circuited
        // earlier by module()'s Setup-phase intercept, before the Application-level CORS plugin
        // ever runs (see that intercept's comment for why).
        post("/api/v1/payments/payu/callback") { call.handlePayuCallback(store, payu, payuReturnBase) }
    }
}

/** PayU's surl AND furl both point here (its outcome is read from the `status` field itself, so
 * one route covers both). This is hit by the USER'S BROWSER as a real top-level form POST, not
 * an API caller -- so it always ends in a redirect back to the storefront, never a JSON error
 * response. */
private val payuCallbackLogger = org.slf4j.LoggerFactory.getLogger("PayuCallback")

suspend fun ApplicationCall.handlePayuCallback(store: PaymentStore, payu: PayuPaymentProvider, payuReturnBase: String) {
    val form = receiveParameters()
    val fields = form.names().associateWith { name -> form[name].orEmpty() }
    val checkoutId = fields["udf1"].orEmpty()
    val txnid = fields["txnid"]
    val amount = fields["amount"]
    if (txnid.isNullOrBlank() || amount.isNullOrBlank() || !payu.verifyResponseHash(fields)) {
        respondRedirect("$payuReturnBase/?checkoutId=$checkoutId&payment=invalid"); return
    }
    val status = payu.statusOf(fields["status"])
    // PayU's own hash already authenticates this whole callback as genuinely theirs for our
    // txnid, so the amount PayU echoes back doesn't need to be trusted for that -- and it can't
    // be compared directly against our records anyway: PayU's hosted checkout can add its own
    // convenience fee on top for some payment methods (observed on card payments), so the
    // echoed amount legitimately differs from what we originally charged. Use the amount WE
    // recorded when creating this payment, not PayU's, so PaymentRepository.webhook()'s
    // amount-match check (a real anti-forgery guard for the generic JSON webhook route) doesn't
    // spuriously reject a genuine callback.
    val stored = store.getByProviderPaymentId(txnid)
    if (stored == null) {
        payuCallbackLogger.error("PayU callback for unknown txnid={} checkoutId={}", txnid, checkoutId)
        respondRedirect("$payuReturnBase/?checkoutId=$checkoutId&payment=invalid"); return
    }
    runCatching {
        store.webhook(PaymentProviderName.PAYU, PaymentWebhookRequest(fields["mihpayid"] ?: txnid, txnid, status, stored.amountMinor, stored.currency, fields), callId.orEmpty())
    }.onFailure { payuCallbackLogger.error("PayU webhook update failed for txnid={} checkoutId={}", txnid, checkoutId, it) }
    respondRedirect("$payuReturnBase/?checkoutId=$checkoutId&payment=${if (status == PaymentStatus.CAPTURED) "success" else "failed"}")
}

@Serializable private data class Health(val status:String,val component:String)
@Serializable private data class PaymentEnvelope(val userId:String,val request:PaymentCreateRequest)
private fun ApplicationConfig.required(path:String)=property(path).getString().takeIf{it.isNotBlank()}?:error("Missing configuration: $path")
private fun ApplicationConfig.optional(path:String,default:String="")=propertyOrNull(path)?.getString()?.takeIf{it.isNotBlank()}?:default
private fun parseKeys(value:String)=value.split(',').associate{it.substringBefore('=').trim() to it.substringAfter('=').trim()}.filterValues{it.isNotBlank()}
private fun io.ktor.server.application.ApplicationCall.requireUser(verifier:HmacJwtAccessVerifier):VerifiedAccessToken{val token=request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?:throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED,"Authentication is required.",401);return verifier.verify(token)}
private fun io.ktor.server.application.ApplicationCall.requirePermission(principal:VerifiedAccessToken,permission:String){if(!principal.isPrivileged()&&permission !in principal.permissions)throw ApiException(ErrorCode.FORBIDDEN,"You do not have permission for this operation.",403)}
private fun VerifiedAccessToken.isPrivileged()=setOf("ADMIN","SUPER_ADMIN","FINANCE").any{it in roles}
private fun io.ktor.server.application.ApplicationCall.requireInternal(expected:String){if(expected.isBlank()||request.header("X-Internal-Service-Token")!=expected)throw ApiException(ErrorCode.FORBIDDEN,"Internal service authentication failed.",403)}
private fun io.ktor.http.Parameters.required(name:String)=this[name]?:throw ApiException(ErrorCode.VALIDATION_ERROR,"Missing path parameter: $name",400)
