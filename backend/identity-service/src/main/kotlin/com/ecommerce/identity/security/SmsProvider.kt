package com.ecommerce.identity.security

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface SmsProvider {
    fun sendOtp(mobile: String, otp: String)
}

/**
 * Real SMS delivery via MSG91's OTP API (https://msg91.com). India regulates transactional SMS
 * under DLT: MSG91 requires a pre-approved OTP template (from the MSG91 dashboard) even with a
 * valid authkey -- [templateId] isn't optional in practice, unlike Resend's ready-to-use test
 * sender. We still generate and own the actual OTP value ourselves (IdentityService's own
 * random code); MSG91's `otp` param just asks it to deliver that exact value instead of one it
 * generates itself, so the code the user receives always matches what the backend checks.
 */
class Msg91SmsProvider(
    private val authKey: String,
    private val templateId: String,
    private val baseUrl: String = "https://control.msg91.com",
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : SmsProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun sendOtp(mobile: String, otp: String) {
        val query = listOf(
            "template_id" to templateId,
            "mobile" to mobile,
            "authkey" to authKey,
            "otp" to otp,
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/v5/otp?$query"))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            throw unavailable()
        }
        val type = runCatching { json.parseToJsonElement(response.body()).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
        if (response.statusCode() !in 200..299 || type != "success") throw unavailable()
    }

    private fun unavailable() = ApiException(
        errorCode = ErrorCode.DEPENDENCY_UNAVAILABLE,
        message = "SMS delivery is temporarily unavailable.",
        statusCode = 503,
        retryable = true,
    )
}
