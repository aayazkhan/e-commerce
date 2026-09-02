package com.ecommerce.platform.service

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class InternalHttpResponse(val statusCode: Int, val body: String)

class InternalHttpClient(private val baseUrl: String, timeout: Duration = Duration.ofSeconds(3)) {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val requestTimeout = timeout
    private val breaker = CircuitBreaker()

    fun get(path: String): InternalHttpResponse = send("GET", path, null, emptyMap())
    fun get(path: String, headers: Map<String, String>): InternalHttpResponse = send("GET", path, null, headers)
    fun post(path: String, body: String): InternalHttpResponse = send("POST", path, body, emptyMap())
    fun post(path: String, body: String, headers: Map<String, String>): InternalHttpResponse = send("POST", path, body, headers)

    private fun send(method: String, path: String, body: String?, headers: Map<String, String>): InternalHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(requestTimeout).header("Accept", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = when (method) {
            "GET" -> builder.GET().build()
            "POST" -> builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.orEmpty())).build()
            else -> error("Unsupported internal method")
        }
        return try {
            breaker.execute {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                InternalHttpResponse(response.statusCode(), response.body())
            }
        } catch (error: Exception) {
            throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Internal service is unavailable.", 503, retryable = true)
        }
    }
}
