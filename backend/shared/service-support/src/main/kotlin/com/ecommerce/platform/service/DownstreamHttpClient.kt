package com.ecommerce.platform.service

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class DownstreamResponse(val status: Int, val body: String, val contentType: String = "application/json")

/** HTTP boundary for orchestration services. It never opens another service's database. */
class DownstreamHttpClient(private val timeout: Duration = Duration.ofSeconds(5)) {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    fun request(baseUrl: String, method: String, path: String, bearer: String? = null, body: String? = null, requestId: String? = null, internalToken: String? = null, actorId: String? = null): DownstreamResponse {
        val breaker = breakers.computeIfAbsent(baseUrl.trimEnd('/')) { CircuitBreaker() }
        return breaker.execute {
            val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(timeout).header("Content-Type", "application/json")
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            if (requestId != null) builder.header("X-Request-Id", requestId)
            if (internalToken != null) builder.header("X-Internal-Service-Token", internalToken)
            if (actorId != null) builder.header("X-Actor-Id", actorId)
            val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            when (method.uppercase()) { "POST" -> builder.POST(publisher); "PUT" -> builder.PUT(publisher); "PATCH" -> builder.method("PATCH", publisher); "DELETE" -> builder.DELETE(); else -> builder.GET() }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            DownstreamResponse(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse("application/json"))
        }
    }
}
