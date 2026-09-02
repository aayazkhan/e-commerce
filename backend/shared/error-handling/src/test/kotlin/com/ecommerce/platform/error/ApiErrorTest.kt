package com.ecommerce.platform.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ApiErrorTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `api exception keeps structured error information`() {
        val violation = FieldViolation("email", "Invalid email")
        val exception = ApiException(ErrorCode.VALIDATION_ERROR, "Request is invalid", 422, retryable = false, fieldViolations = listOf(violation))

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.errorCode)
        assertEquals(422, exception.statusCode)
        assertEquals(listOf(violation), exception.fieldViolations)
        assertEquals("Request is invalid", exception.message)
    }

    @Test
    fun `error code enum rejects unknown values`() {
        assertFailsWith<IllegalArgumentException> { ErrorCode.valueOf("NOT_A_REAL_ERROR") }
    }

    @Test
    fun `api error serialization preserves violations and both retryable defaults`() {
        val withViolation = ApiError(
            ErrorCode.VALIDATION_ERROR,
            "Request is invalid",
            "req-1",
            listOf(FieldViolation("email", "Invalid email")),
            retryable = true,
        )
        assertEquals(withViolation, json.decodeFromString<ApiError>(json.encodeToString(withViolation)))

        val withoutViolation = ApiError(ErrorCode.NOT_FOUND, "Missing", "req-2")
        assertEquals(withoutViolation, json.decodeFromString<ApiError>(json.encodeToString(withoutViolation)))
        assertEquals(FieldViolation("sku", "Required"), json.decodeFromString<FieldViolation>(json.encodeToString(FieldViolation("sku", "Required"))))
    }

    @Test
    fun `api error decoder applies omitted optional fields`() {
        val decoded = json.decodeFromString<ApiError>("{\"code\":\"NOT_FOUND\",\"message\":\"Missing\",\"requestId\":\"req-3\"}")

        assertEquals(ApiError(ErrorCode.NOT_FOUND, "Missing", "req-3"), decoded)
        assertEquals(emptyList(), decoded.fieldViolations)
        assertEquals(false, decoded.retryable)

        val compact = Json { encodeDefaults = false; explicitNulls = false }
        assertEquals(
            "{\"code\":\"NOT_FOUND\",\"message\":\"Missing\",\"requestId\":\"req-3\"}",
            compact.encodeToString(ApiError(ErrorCode.NOT_FOUND, "Missing", "req-3")),
        )
        val compactWithFields = ApiError(ErrorCode.VALIDATION_ERROR, "Invalid", "req-4", listOf(FieldViolation("sku", "Required")), true)
        assertEquals(compactWithFields, compact.decodeFromString<ApiError>(compact.encodeToString(compactWithFields)))
    }

    @Test
    fun `api error decoder rejects missing required fields`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ApiError>("{}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<ApiError>("{\"message\":\"Missing\",\"requestId\":\"req-5\"}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<ApiError>("{\"code\":\"NOT_FOUND\",\"requestId\":\"req-5\"}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<FieldViolation>("{\"field\":\"sku\"}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<FieldViolation>("{\"message\":\"Required\"}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<FieldViolation>("{\"field\":null,\"message\":\"Required\"}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<FieldViolation>("{\"field\":\"sku\",\"message\":null}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<ApiError>("{\"code\":null,\"message\":\"Missing\",\"requestId\":\"req-5\"}")
        }
    }

    @Test
    fun `api error decoder applies each optional field independently`() {
        val withViolationsOnly = json.decodeFromString<ApiError>(
            "{\"code\":\"VALIDATION_ERROR\",\"message\":\"Invalid\",\"requestId\":\"req-6\",\"fieldViolations\":[{\"field\":\"sku\",\"message\":\"Required\"}]}"
        )
        assertEquals(1, withViolationsOnly.fieldViolations.size)
        assertEquals(false, withViolationsOnly.retryable)

        val retryableOnly = json.decodeFromString<ApiError>(
            "{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"message\":\"Retry\",\"requestId\":\"req-7\",\"retryable\":true}"
        )
        assertEquals(emptyList(), retryableOnly.fieldViolations)
        assertEquals(true, retryableOnly.retryable)
    }

    @Test
    fun `api error copies preserve required and default fields`() {
        val original = ApiError(
            ErrorCode.CONFLICT,
            "Conflict",
            "req-8",
            listOf(FieldViolation("sku", "Already exists")),
            retryable = true,
        )

        assertEquals(ApiError(ErrorCode.NOT_FOUND, "Conflict", "req-8", original.fieldViolations, true), original.copy(code = ErrorCode.NOT_FOUND))
        assertEquals(ApiError(ErrorCode.CONFLICT, "Missing", "req-8", original.fieldViolations, true), original.copy(message = "Missing"))
        assertEquals(ApiError(ErrorCode.CONFLICT, "Conflict", "req-9", original.fieldViolations, true), original.copy(requestId = "req-9"))
        assertEquals(ApiError(ErrorCode.CONFLICT, "Conflict", "req-8"), original.copy(fieldViolations = emptyList(), retryable = false))
        assertEquals(original, original.copy())
        assertEquals(FieldViolation("name", "Required"), FieldViolation("sku", "Already exists").copy(field = "name", message = "Required"))
    }

    @Test
    fun `error payloads decode when required fields arrive out of order`() {
        assertEquals(
            FieldViolation("sku", "Required"),
            json.decodeFromString<FieldViolation>("{\"message\":\"Required\",\"field\":\"sku\"}"),
        )
        assertEquals(
            ApiError(ErrorCode.CONFLICT, "Conflict", "req-10", retryable = true),
            json.decodeFromString<ApiError>("{\"retryable\":true,\"requestId\":\"req-10\",\"message\":\"Conflict\",\"code\":\"CONFLICT\"}"),
        )
    }
}
