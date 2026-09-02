package com.ecommerce.payment

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.InternalHttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CodPaymentProvider : PaymentProvider {
    override val name = PaymentProviderName.COD
    override fun create(request: ProviderCreateRequest) = ProviderPayment("cod-${request.paymentId}", PaymentStatus.AUTHORIZED)
    override fun query(providerPaymentId: String) = ProviderPayment(providerPaymentId, PaymentStatus.AUTHORIZED)
    override fun refund(providerPaymentId: String, amountMinor: Long, currency: String, idempotencyKey: String) = ProviderRefund("cod-refund-$idempotencyKey", PaymentStatus.REFUNDED)
    override fun verifyWebhook(body: String, signature: String?) = false
}

class HttpPaymentProvider(private val baseUrl: String, private val apiKey: String, private val webhookSecret: String, private val json: Json = Json { ignoreUnknownKeys = true }) : PaymentProvider {
    override val name = PaymentProviderName.HTTP
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    override fun create(request: ProviderCreateRequest): ProviderPayment = call("POST", "/payments", """{"orderId":"${esc(request.orderId)}","amountMinor":${request.amountMinor},"currency":"${esc(request.currency)}","paymentMethodToken":"${esc(request.paymentMethodToken)}","returnUrl":${request.returnUrl?.let { "\"${esc(it)}\"" } ?: "null"}}""").toProviderPayment()
    override fun query(providerPaymentId: String): ProviderPayment = call("GET", "/payments/${esc(providerPaymentId)}", null).toProviderPayment()
    override fun refund(providerPaymentId: String, amountMinor: Long, currency: String, idempotencyKey: String): ProviderRefund { val response = call("POST", "/payments/${esc(providerPaymentId)}/refund", """{"amountMinor":$amountMinor,"currency":"${esc(currency)}"}""", mapOf("Idempotency-Key" to idempotencyKey)); val body=json.parseToJsonElement(response.body).jsonObject; return ProviderRefund(body["id"]?.jsonPrimitive?.content, body["status"]?.jsonPrimitive?.content?.let(::status) ?: PaymentStatus.REFUNDED) }
    override fun verifyWebhook(body: String, signature: String?): Boolean { if (webhookSecret.isBlank() || signature.isNullOrBlank()) return false; val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(webhookSecret.toByteArray(StandardCharsets.UTF_8),"HmacSHA256"));val expected=mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString(""){ "%02x".format(it) };return MessageDigest.isEqual(expected.toByteArray(),signature.removePrefix("sha256=").toByteArray()) }
    private fun call(method: String, path: String, body: String?, headers: Map<String,String> = emptyMap()): InternalHttpResponse { if (baseUrl.isBlank() || apiKey.isBlank()) throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Payment provider is not configured.",503,true); val builder=HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/')+path)).timeout(Duration.ofSeconds(8)).header("Accept","application/json").header("Authorization","Bearer $apiKey");headers.forEach{(k,v)->builder.header(k,v)};val request=if(method=="GET")builder.GET().build() else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.orEmpty())).build();return try{val r=client.send(request,HttpResponse.BodyHandlers.ofString());if(r.statusCode() !in 200..299)throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Payment provider rejected the request.",502,true);InternalHttpResponse(r.statusCode(),r.body())}catch(e:ApiException){throw e}catch(_:Exception){throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Payment provider is unavailable.",503,true)} }
    private fun InternalHttpResponse.toProviderPayment(): ProviderPayment { val body=json.parseToJsonElement(body).jsonObject;return ProviderPayment(body["id"]?.jsonPrimitive?.content ?: body["providerPaymentId"]?.jsonPrimitive?.content ?: throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Payment provider response is invalid.",502,true),body["status"]?.jsonPrimitive?.content?.let(::status) ?: PaymentStatus.PROCESSING,body["clientSecret"]?.jsonPrimitive?.content) }
    private fun status(value:String)=when(value.uppercase()){ "SUCCEEDED","CAPTURED","PAID"->PaymentStatus.CAPTURED;"AUTHORIZED","AUTHORISED"->PaymentStatus.AUTHORIZED;"REQUIRES_ACTION","REQUIRES_PAYMENT_METHOD"->PaymentStatus.REQUIRES_ACTION;"FAILED","CANCELED","CANCELLED"->PaymentStatus.FAILED;else->PaymentStatus.PROCESSING }
    private fun esc(value:String)=value.replace("\\","\\\\").replace("\"","\\\"")
}
