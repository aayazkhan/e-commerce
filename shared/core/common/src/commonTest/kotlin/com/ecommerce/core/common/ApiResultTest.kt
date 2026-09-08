package com.ecommerce.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiResultTest {
    @Test
    fun `map transforms only a successful result`() {
        val success: ApiResult<Int> = ApiResult.Success(2)
        assertEquals(ApiResult.Success(4), success.map { it * 2 })

        val error: ApiResult<Int> = ApiResult.ApiError(404, ApiErrorBody("NOT_FOUND", "missing", "req-1"))
        assertEquals(error, error.map { it * 2 })
    }

    @Test
    fun `userMessage surfaces the server message for api errors and null for success`() {
        val body = ApiErrorBody("VALIDATION_ERROR", "Email is required.", "req-1")
        assertEquals("Email is required.", ApiResult.ApiError(400, body).userMessage())
        assertNull(ApiResult.Success("ok").userMessage())
    }

    @Test
    fun `userMessage gives a generic message for a network error`() {
        val result = ApiResult.NetworkError(TransportFailure("boom"))
        assertEquals("Couldn't reach the server. Check your connection and try again.", result.userMessage())
    }

    @Test
    fun `page exposes hasMore based on the cursor`() {
        assertEquals(true, Page(listOf(1, 2), nextCursor = "cur_1").hasMore)
        assertEquals(false, Page(listOf(1, 2), nextCursor = null).hasMore)
    }
}
