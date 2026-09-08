package com.ecommerce.core.network

import com.ecommerce.core.common.ApiErrorBody
import com.ecommerce.core.common.ApiResult
import com.ecommerce.core.common.TransportFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Thin wrapper around a Ktor [HttpClient] that talks to the gateway (never a service's own port
 * directly -- see api-gateway's ServiceRoutes.kt for why the prefix matters), injects the bearer
 * token from [session], and maps every response into [ApiResult] so callers never see a raw
 * exception or an unparsed error body.
 */
class ApiClient(
    @PublishedApi internal val http: HttpClient,
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val session: SessionHolder,
) {
    suspend inline fun <reified T> get(path: String): ApiResult<T> {
        val response = try {
            http.request(baseUrl.trimEnd('/') + path) {
                method = HttpMethod.Companion.Get
                session.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        } catch (t: Throwable) {
            return ApiResult.NetworkError(TransportFailure(t.message ?: "Request failed", t))
        }
        return response.toApiResult()
    }

    suspend inline fun <reified T, reified B> post(path: String, requestBody: B): ApiResult<T> {
        val response = try {
            http.request(baseUrl.trimEnd('/') + path) {
                method = HttpMethod.Companion.Post
                session.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        } catch (t: Throwable) {
            return ApiResult.NetworkError(TransportFailure(t.message ?: "Request failed", t))
        }
        return response.toApiResult()
    }

    suspend inline fun <reified T> HttpResponse.toApiResult(): ApiResult<T> = try {
        if (status.isSuccess()) {
            ApiResult.Success(body<T>())
        } else {
            ApiResult.ApiError(status.value, body<ApiErrorBody>())
        }
    } catch (t: Throwable) {
        ApiResult.NetworkError(TransportFailure("Couldn't read the server response.", t))
    }
}
