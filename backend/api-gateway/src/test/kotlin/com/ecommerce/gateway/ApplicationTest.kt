package com.ecommerce.gateway

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `live endpoint returns request id and healthy response`() = testApplication {
        application { module() }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers.contains("X-Request-Id"))
        assertTrue(response.bodyAsText().contains("api-gateway"))
    }
}
