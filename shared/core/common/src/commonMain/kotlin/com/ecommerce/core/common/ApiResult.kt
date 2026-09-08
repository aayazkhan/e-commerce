package com.ecommerce.core.common

import kotlinx.serialization.Serializable

/**
 * Matches the ApiError shape every backend service actually serializes (verified directly against
 * StatusPages handlers across services, e.g. backend/payment-service/.../Application.kt) -- fields
 * are flat on the response body, NOT nested under an "error" key as API.md's illustrative envelope
 * shows.
 */
@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val requestId: String,
    val fieldViolations: List<FieldViolation> = emptyList(),
    val retryable: Boolean = false,
)

@Serializable
data class FieldViolation(val field: String, val message: String)

/** A network/transport failure that never reached the server with a structured error body. */
data class TransportFailure(val message: String, val cause: Throwable? = null)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class ApiError(val status: Int, val body: ApiErrorBody) : ApiResult<Nothing>
    data class NetworkError(val failure: TransportFailure) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.ApiError -> this
    is ApiResult.NetworkError -> this
}

/** The message safe to show a user, regardless of which branch of ApiResult failed. */
fun ApiResult<*>.userMessage(): String? = when (this) {
    is ApiResult.Success -> null
    is ApiResult.ApiError -> body.message
    is ApiResult.NetworkError -> "Couldn't reach the server. Check your connection and try again."
}
