package com.ecommerce.gateway

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.util.toMap

/** Headers that must not be copied across a hop; the client/engine manage these. */
private val hopByHopHeaders = setOf(
    HttpHeaders.Connection,
    "Keep-Alive",
    HttpHeaders.ProxyAuthenticate,
    HttpHeaders.ProxyAuthorization,
    HttpHeaders.TE,
    HttpHeaders.Trailer,
    HttpHeaders.TransferEncoding,
    HttpHeaders.Upgrade,
    HttpHeaders.Host,
    HttpHeaders.ContentLength,
    HttpHeaders.ContentType,
    HttpHeaders.AcceptEncoding,
).map { it.lowercase() }.toSet()

/**
 * CORS response headers are set by the gateway's own CORS plugin for the client-facing response.
 * A proxied service installs CORS too (for when it's called directly), so its response already
 * carries these -- forwarding them here would duplicate the header and browsers reject a response
 * with Access-Control-Allow-Origin appearing more than once.
 */
private val corsResponseHeaders = setOf(
    "Access-Control-Allow-Origin",
    "Access-Control-Allow-Credentials",
    "Access-Control-Allow-Methods",
    "Access-Control-Allow-Headers",
    "Access-Control-Expose-Headers",
    "Access-Control-Max-Age",
).map { it.lowercase() }.toSet()

suspend fun proxyToService(call: ApplicationCall, client: HttpClient) {
    val requestUri = call.request.uri
    val path = requestUri.substringBefore('?')
    val route = resolveServiceRoute(path)
        ?: throw ApiException(ErrorCode.NOT_FOUND, "No route for $path", 404)

    val targetUrl = route.baseUrl.trimEnd('/') + requestUri
    val requestBody = if (call.request.httpMethod in bodyCarryingMethods) call.receive<ByteArray>() else null

    val response: HttpResponse = client.request(targetUrl) {
        method = call.request.httpMethod
        headers {
            call.request.headers.toMap().forEach { (key, values) ->
                if (key.lowercase() !in hopByHopHeaders) {
                    values.forEach { append(key, it) }
                }
            }
        }
        call.request.headers[HttpHeaders.ContentType]?.let { contentType(ContentType.parse(it)) }
        if (requestBody != null) {
            setBody(requestBody)
        }
    }

    response.headers.toMap().forEach { (key, values) ->
        if (key.lowercase() !in hopByHopHeaders && key.lowercase() !in corsResponseHeaders) {
            values.forEach { call.response.header(key, it) }
        }
    }
    call.respondBytes(
        bytes = response.readBytes(),
        contentType = response.contentType(),
        status = response.status,
    )
}

private val bodyCarryingMethods = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)
