package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthApiTest {
    @Test
    fun `login parses a successful auth response`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("http://localhost:8080/api/v1/auth/login", request.url.toString())
            respond(
                content = """{"accessToken":"tok","refreshToken":"ref","expiresIn":900,"sessionId":"sess-1","user":{"id":"user-1","status":"ACTIVE"}}""",
                status = HttpStatusCode.Companion.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = AuthApi(ApiClient(createHttpClient(engine), "http://localhost:8080", SessionHolder()))

        val result = api.login("seller@example.com", "hunter2")

        assertIs<ApiResult.Success<AuthResponse>>(result)
        assertEquals("tok", result.value.accessToken)
        assertEquals("user-1", result.value.user.id)
    }

    @Test
    fun `login surfaces the server error message on invalid credentials`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"code":"AUTHENTICATION_REQUIRED","message":"Invalid credentials.","requestId":"req-1"}""",
                status = HttpStatusCode.Companion.Unauthorized,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val api = AuthApi(ApiClient(createHttpClient(engine), "http://localhost:8080", SessionHolder()))

        val result = api.login("seller@example.com", "wrong")

        assertIs<ApiResult.ApiError>(result)
        assertEquals(401, result.status)
        assertEquals("Invalid credentials.", result.body.message)
    }

    @Test
    fun `login reports a network error when the server is unreachable`() = runTest {
        val engine = MockEngine { throw kotlin.RuntimeException("connection refused") }
        val api = AuthApi(ApiClient(createHttpClient(engine), "http://localhost:8080", SessionHolder()))

        val result = api.login("seller@example.com", "hunter2")

        assertIs<ApiResult.NetworkError>(result)
    }
}
