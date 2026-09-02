package com.ecommerce.shipping

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
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HttpShippingProvider(private val baseUrl:String,private val apiKey:String,private val webhookSecret:String,private val json:Json=Json{ignoreUnknownKeys=true}):ShippingProvider{
    override val name="http"
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    override fun quote(request:ShippingQuoteRequest)=call("POST","/quotes",json.encodeToString(request)).let{val o=json.parseToJsonElement(it.body).jsonObject;ShippingQuote(o["id"]?.jsonPrimitive?.content?:throw invalid(),request.method,o["amountMinor"]?.jsonPrimitive?.content?.toLongOrNull()?:throw invalid(),request.currency,o["expiresAt"]?.jsonPrimitive?.content?:throw invalid())}
    override fun create(request:ShipmentCreateRequest)=call("POST","/shipments",json.encodeToString(request)).toShipment()
    override fun track(providerShipmentId:String)=call("GET","/shipments/$providerShipmentId",null).toShipment()
    override fun cancel(providerShipmentId:String)=call("POST","/shipments/$providerShipmentId/cancel","{}").toShipment()
    override fun verifyWebhook(body:String,signature:String?):Boolean{if(webhookSecret.isBlank()||signature.isNullOrBlank())return false;val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(webhookSecret.toByteArray(StandardCharsets.UTF_8),"HmacSHA256"));val expected=mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString(""){ "%02x".format(it) };return MessageDigest.isEqual(expected.toByteArray(),signature.removePrefix("sha256=").toByteArray())}
    private fun call(method:String,path:String,body:String?):InternalHttpResponse{if(baseUrl.isBlank()||apiKey.isBlank())throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Shipping provider is not configured.",503,true);val b=HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/')+path)).timeout(Duration.ofSeconds(8)).header("Accept","application/json").header("Authorization","Bearer $apiKey");val r=try{client.send(if(method=="GET")b.GET().build() else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.orEmpty())).build(),HttpResponse.BodyHandlers.ofString())}catch(_:Exception){throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Shipping provider is unavailable.",503,true)};if(r.statusCode() !in 200..299)throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Shipping provider rejected the request.",502,true);return InternalHttpResponse(r.statusCode(),r.body())}
    private fun InternalHttpResponse.toShipment():ProviderShipment{val o=json.parseToJsonElement(body).jsonObject;return ProviderShipment(o["id"]?.jsonPrimitive?.content?:o["providerShipmentId"]?.jsonPrimitive?.content?:throw invalid(),o["status"]?.jsonPrimitive?.content?.let{runCatching{ShipmentStatus.valueOf(it.uppercase())}.getOrDefault(ShipmentStatus.IN_TRANSIT)}?:ShipmentStatus.LABEL_CREATED,o["trackingNumber"]?.jsonPrimitive?.content,o["carrier"]?.jsonPrimitive?.content)}
    private fun invalid()=ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,"Shipping provider response is invalid.",502,true)
}
