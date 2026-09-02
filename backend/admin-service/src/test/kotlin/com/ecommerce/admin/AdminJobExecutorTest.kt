package com.ecommerce.admin

import com.ecommerce.platform.service.DownstreamResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminJobExecutorTest {
    private val item = JobItem(7, "job-1", "product-1", "PRODUCT_PUBLISH", "admin-1")

    @Test
    fun `successful product publish produces completed execution and forwards internal identity`() {
        var request: List<String> = emptyList()

        val execution = executeAdminJob(item, "http://catalog", "internal-secret") { baseUrl, method, path, token, actorId ->
            request = listOf(baseUrl, method, path, token, actorId)
            DownstreamResponse(204, "")
        }

        assertEquals(AdminJobExecution(true, null), execution)
        assertEquals(
            listOf("http://catalog", "POST", "/api/v1/internal/products/product-1/publish", "internal-secret", "admin-1"),
            request,
        )
    }

    @Test
    fun `provider failure returns retryable execution and truncates persisted error`() {
        val execution = executeAdminJob(item, "http://catalog", "internal-secret") { _, _, _, _, _ ->
            DownstreamResponse(503, "x".repeat(700))
        }

        assertEquals(false, execution.success)
        assertEquals(500, execution.error?.length)
    }

    @Test
    fun `provider exception preserves message and uses safe fallback for a nameless exception`() {
        val failure = executeAdminJob(item, "http://catalog", "internal-secret") { _, _, _, _, _ ->
            throw IllegalStateException("catalog unavailable")
        }
        assertEquals(AdminJobExecution(false, "catalog unavailable"), failure)

        val nameless = executeAdminJob(item, "http://catalog", "internal-secret") { _, _, _, _, _ ->
            throw Throwable()
        }
        assertEquals(AdminJobExecution(false, "request failed"), nameless)
    }

    @Test
    fun `unknown job type is recorded as a failed execution without invoking a provider`() {
        var invoked = false
        val execution = executeAdminJob(item.copy(jobType = "USER_EXPORT"), "http://catalog", "internal-secret") { _, _, _, _, _ ->
            invoked = true
            DownstreamResponse(200, "unexpected")
        }

        assertEquals(AdminJobExecution(false, "No executor configured"), execution)
        assertEquals(false, invoked)
    }
}
