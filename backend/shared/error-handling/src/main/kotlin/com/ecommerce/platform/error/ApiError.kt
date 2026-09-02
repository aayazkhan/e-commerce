package com.ecommerce.platform.error

import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode {
    VALIDATION_ERROR,
    AUTHENTICATION_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    DEPENDENCY_UNAVAILABLE,
    INTERNAL_ERROR,
}

@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
    val requestId: String,
    val fieldViolations: List<FieldViolation> = emptyList(),
    val retryable: Boolean = false,
)

@Serializable
data class FieldViolation(
    val field: String,
    val message: String,
)

class ApiException(
    val errorCode: ErrorCode,
    override val message: String,
    val statusCode: Int,
    val retryable: Boolean = false,
    val fieldViolations: List<FieldViolation> = emptyList(),
) : RuntimeException(message)
