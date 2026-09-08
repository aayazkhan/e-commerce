package com.ecommerce.payment

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.InternalHttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
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

/** The exact fields a browser must POST (as an HTML form, not fetch/XHR -- PayU's hosted page
 * requires a real top-level navigation) to PayU's hosted checkout. Handed to the client as
 * ProviderPayment.clientSecret so the frontend can build and auto-submit that form. */
@Serializable
data class PayuFormFields(
    val actionUrl: String,
    val key: String,
    val txnid: String,
    val amount: String,
    val productinfo: String,
    val firstname: String,
    val email: String,
    val phone: String,
    val surl: String,
    val furl: String,
    val udf1: String,
    val hash: String,
    @kotlinx.serialization.SerialName("service_provider") val serviceProvider: String = "payu_paisa",
)

/**
 * PayU's classic hosted-checkout ("form2") integration: the merchant builds a signed form,
 * the user's browser POSTs it directly to PayU, and PayU redirects the browser back to our own
 * surl/furl with a reverse-signed result. This is why PayU can't fit PaymentProvider's synchronous
 * create()-returns-final-status shape the way COD/generic-HTTP do -- create() only ever returns
 * REQUIRES_ACTION with the form to submit; the callback route (see Application.kt) is what
 * actually finalizes the payment once PayU redirects back, and query() (used by
 * PaymentRepository.reconcile()) is the fallback for when the browser never makes it back at all.
 *
 * Test/demo credentials (PayU's own publicly documented sandbox merchant): key "gtKFFx",
 * salt "4R38IvwiV57FwVpsgOvTXBdLE4tHUXFW", base URL https://test.payu.in. Swap these for a real merchant's key/salt to
 * go beyond a demo -- see ops/local/run-payment.sh.
 */
class PayuPaymentProvider(
    private val merchantKey: String,
    private val merchantSalt: String,
    private val baseUrl: String,
    private val successUrl: String,
    private val failureUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PaymentProvider {
    override val name = PaymentProviderName.PAYU
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun create(request: ProviderCreateRequest): ProviderPayment {
        if (merchantKey.isBlank() || merchantSalt.isBlank()) throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "PayU is not configured.", 503, true)
        val txnid = "txn" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()
        val amount = "%.2f".format(request.amountMinor / 100.0)
        val productinfo = request.orderId.take(100)
        val firstname = (request.customerName?.takeIf { it.isNotBlank() } ?: "Customer").take(60)
        val email = (request.customerEmail?.takeIf { it.isNotBlank() } ?: "customer@example.com").take(60)
        val phone = (request.customerPhone?.takeIf { it.isNotBlank() } ?: "9999999999").take(20)
        // udf1 carries checkout-service's checkoutId through PayU and back to our callback route,
        // so the browser can be redirected to the right storefront screen afterwards. udf2-udf10
        // stay empty. PayU's request hash: key|txnid|amount|productinfo|firstname|email|
        // udf1|udf2|...|udf10|salt (10 udf slots total).
        val udf1 = request.checkoutId.orEmpty()
        val hash = sha512((listOf(merchantKey, txnid, amount, productinfo, firstname, email, udf1) + List(9) { "" } + merchantSalt).joinToString("|"))
        val fields = PayuFormFields(
            actionUrl = "${baseUrl.trimEnd('/')}/_payment",
            key = merchantKey,
            txnid = txnid,
            amount = amount,
            productinfo = productinfo,
            firstname = firstname,
            email = email,
            phone = phone,
            surl = successUrl,
            furl = failureUrl,
            udf1 = udf1,
            hash = hash,
        )
        return ProviderPayment(txnid, PaymentStatus.REQUIRES_ACTION, json.encodeToString(fields))
    }

    override fun query(providerPaymentId: String): ProviderPayment {
        val details = verifyPayment(providerPaymentId) ?: return ProviderPayment(providerPaymentId, PaymentStatus.PROCESSING)
        return ProviderPayment(providerPaymentId, statusOf(details["status"]?.jsonPrimitive?.content))
    }

    override fun refund(providerPaymentId: String, amountMinor: Long, currency: String, idempotencyKey: String): ProviderRefund {
        val details = verifyPayment(providerPaymentId) ?: throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Could not locate the PayU transaction to refund.", 503, true)
        val mihpayid = details["mihpayid"]?.jsonPrimitive?.content ?: throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "PayU did not return a transaction reference to refund.", 503, true)
        val amount = "%.2f".format(amountMinor / 100.0)
        val refundHash = sha512("$merchantKey|cancel_refund_transaction|$mihpayid|$merchantSalt")
        val body = "key=${enc(merchantKey)}&command=cancel_refund_transaction&var1=${enc(mihpayid)}&var2=${enc(idempotencyKey)}&var3=$amount&hash=${enc(refundHash)}"
        val response = json.parseToJsonElement(postForm(body)).jsonObject
        val succeeded = response["status"]?.jsonPrimitive?.content == "1"
        return ProviderRefund(mihpayid, if (succeeded) PaymentStatus.REFUNDED else PaymentStatus.FAILED)
    }

    /** PayU's surl/furl callback is a form POST from the user's browser, not the generic
     * signed-JSON webhook this interface's verifyWebhook() models -- so this always returns
     * false, and the callback route (Application.kt) calls [verifyResponseHash] directly instead. */
    override fun verifyWebhook(body: String, signature: String?) = false

    /** Verifies PayU's reverse hash on the surl/furl callback: salt|status|udf10..udf1|email|
     * firstname|productinfo|amount|txnid|key. udf1 is whatever checkoutId we sent in create()
     * (PayU echoes it back unchanged); udf2-udf10 stay empty, matching create()'s request hash. */
    fun verifyResponseHash(fields: Map<String, String>): Boolean {
        val status = fields["status"] ?: return false
        val txnid = fields["txnid"] ?: return false
        val amount = fields["amount"] ?: return false
        val productinfo = fields["productinfo"] ?: return false
        val firstname = fields["firstname"] ?: return false
        val email = fields["email"] ?: return false
        val udf1 = fields["udf1"].orEmpty()
        val expected = sha512((listOf(merchantSalt, status) + List(9) { "" } + listOf(udf1, email, firstname, productinfo, amount, txnid, merchantKey)).joinToString("|"))
        return expected.equals(fields["hash"], ignoreCase = true)
    }

    fun statusOf(payuStatus: String?) = when (payuStatus?.lowercase()) {
        "success" -> PaymentStatus.CAPTURED
        "pending" -> PaymentStatus.PROCESSING
        "failure", "failed" -> PaymentStatus.FAILED
        else -> PaymentStatus.PROCESSING
    }

    private fun verifyPayment(txnid: String): kotlinx.serialization.json.JsonObject? {
        val hash = sha512("$merchantKey|verify_payment|$txnid|$merchantSalt")
        val body = "key=${enc(merchantKey)}&command=verify_payment&var1=${enc(txnid)}&hash=${enc(hash)}"
        val root = json.parseToJsonElement(postForm(body)).jsonObject
        return root["transaction_details"]?.jsonObject?.get(txnid)?.jsonObject
    }

    private fun postForm(body: String): String {
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/merchant/postservice?form=2"))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "PayU rejected the request.", 502, true)
            response.body()
        } catch (e: ApiException) {
            throw e
        } catch (_: Exception) {
            throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "PayU is unavailable.", 503, true)
        }
    }

    private fun sha512(value: String) = MessageDigest.getInstance("SHA-512").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
