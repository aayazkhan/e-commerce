package com.ecommerce.identity.security

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface EmailProvider {
    fun send(to: String, subject: String, html: String)
}

/**
 * Real transactional email via Resend's API (https://resend.com). "onboarding@resend.dev" is
 * Resend's own always-available test sender -- fine for a demo/local-dev account that hasn't
 * verified a custom sending domain yet; a verified domain's address should replace it for
 * anything beyond that (see ops/local/run-identity.sh for the env vars).
 */
class ResendEmailProvider(
    private val apiKey: String,
    private val fromAddress: String,
    private val baseUrl: String = "https://api.resend.com",
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : EmailProvider {
    private val json = Json { encodeDefaults = true }

    override fun send(to: String, subject: String, html: String) {
        val body = json.encodeToString(ResendEmailRequest(fromAddress, listOf(to), subject, html))
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/emails"))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            throw unavailable()
        }
        if (response.statusCode() !in 200..299) throw unavailable()
    }

    private fun unavailable() = ApiException(
        errorCode = ErrorCode.DEPENDENCY_UNAVAILABLE,
        message = "Email delivery is temporarily unavailable.",
        statusCode = 503,
        retryable = true,
    )
}

@Serializable
private data class ResendEmailRequest(val from: String, val to: List<String>, val subject: String, val html: String)
