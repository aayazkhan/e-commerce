package com.ecommerce.admin

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import com.ecommerce.platform.service.DownstreamResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminRoutesTest {
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("key-1" to "secret"))
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val config = AdminRouteConfig("analytics", "identity", "seller", "catalog", "order", "audit", "cms", "flags")

    @Test
    fun `admin proxy routes enforce permission and preserve downstream status and request details`() = testApplication {
        val proxy = FakeAdminProxy()
        application { installRoutes(FakeAdminStore(), proxy) }
        val ordinary = token()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/dashboard").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/dashboard") { auth(ordinary) }.status)

        val admin = token(roles = listOf("ADMIN"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard") { auth(admin) }.status)
        assertEquals("analytics", proxy.lastBase)
        assertEquals("/api/v1/analytics/summary", proxy.lastPath)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard/sales") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard/orders") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard/customers") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard/products") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard/inventory") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard/payments") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/dashboard/refunds") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/users") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/users/user-1") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/admin/users/user-1/status") { auth(admin); contentType(ContentType.Application.Json); setBody("{\"status\":\"SUSPENDED\"}") }.status)
        assertEquals("PATCH", proxy.lastMethod)
        assertEquals("{\"status\":\"SUSPENDED\"}", proxy.lastBody)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/users/user-1/suspend") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/users/user-1/restore") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/products?limit=2&cursor=c1") { auth(admin) }.status)
        assertTrue(proxy.lastPath!!.contains("limit=2"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/products?limit=") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/products/product-1/publish") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/sellers") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/sellers/seller-1/status") { auth(admin); contentType(ContentType.Application.Json); setBody("{\"status\":\"ACTIVE\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/orders") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/admin/orders/order-1/status") { auth(admin); contentType(ContentType.Application.Json); setBody("{\"status\":\"PAID\"}") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/audit") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/audit/event-1") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/cms/pages") { auth(admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/feature-flags") { auth(admin) }.status)
        proxy.response = DownstreamResponse(503, "dependency unavailable")
        val failed = client.get("/api/v1/admin/dashboard/payments") { auth(admin) }
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        assertEquals("dependency unavailable", failed.bodyAsText())
    }

    @Test
    fun `bulk publish and job lookup use repository and map missing job`() = testApplication {
        val store = FakeAdminStore()
        application { installRoutes(store, FakeAdminProxy()) }
        val admin = token(roles = listOf("ADMIN"))
        val accepted = client.post("/api/v1/admin/products/bulk-publish") { auth(admin); contentType(ContentType.Application.Json); setBody("{\"itemIds\":[\"p1\",\"p2\"],\"payloadJson\":\"{\\\"status\\\":\\\"ACTIVE\\\"}\"}") }
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals("PRODUCT_PUBLISH", store.lastType)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/jobs/job-1") { auth(admin) }.status)
        val missing = client.get("/api/v1/admin/jobs/missing") { auth(admin) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `every protected admin route denies a principal without its permission`() = testApplication {
        application { installRoutes(FakeAdminStore(), FakeAdminProxy()) }
        val ordinary = token()
        val protectedRoutes = listOf(
            HttpMethod.Get to "/api/v1/admin/dashboard/sales",
            HttpMethod.Get to "/api/v1/admin/dashboard/orders",
            HttpMethod.Get to "/api/v1/admin/dashboard/customers",
            HttpMethod.Get to "/api/v1/admin/dashboard/products",
            HttpMethod.Get to "/api/v1/admin/dashboard/inventory",
            HttpMethod.Get to "/api/v1/admin/dashboard/payments",
            HttpMethod.Get to "/api/v1/admin/dashboard/refunds",
            HttpMethod.Get to "/api/v1/admin/users",
            HttpMethod.Get to "/api/v1/admin/users/user-1",
            HttpMethod.Patch to "/api/v1/admin/users/user-1/status",
            HttpMethod.Post to "/api/v1/admin/users/user-1/suspend",
            HttpMethod.Post to "/api/v1/admin/users/user-1/restore",
            HttpMethod.Get to "/api/v1/admin/sellers",
            HttpMethod.Post to "/api/v1/admin/sellers/seller-1/status",
            HttpMethod.Get to "/api/v1/admin/products",
            HttpMethod.Post to "/api/v1/admin/products/product-1/publish",
            HttpMethod.Post to "/api/v1/admin/products/bulk-publish",
            HttpMethod.Get to "/api/v1/admin/orders",
            HttpMethod.Post to "/api/v1/admin/orders/order-1/status",
            HttpMethod.Get to "/api/v1/admin/audit",
            HttpMethod.Get to "/api/v1/admin/audit/event-1",
            HttpMethod.Get to "/api/v1/admin/cms/pages",
            HttpMethod.Get to "/api/v1/admin/feature-flags",
            HttpMethod.Get to "/api/v1/admin/jobs/job-1",
        )

        protectedRoutes.forEach { (method, path) ->
            val response = client.request(path) {
                this.method = method
                auth(ordinary)
            }
            assertEquals(HttpStatusCode.Forbidden, response.status, "$method $path")
        }
    }

    private fun io.ktor.server.application.Application.installRoutes(store: AdminStore, proxy: AdminProxy) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureAdminRoutes(store, proxy, verifier, config) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun token(roles: List<String> = emptyList(), permissions: List<String> = emptyList()): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"key-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"admin-1\",\"roles\":[${roles.joinToString(",") { "\"$it\"" }}],\"permissions\":[${permissions.joinToString(",") { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeAdminProxy : AdminProxy {
        var response = DownstreamResponse(200, "{\"ok\":true}")
        var lastBase: String? = null
        var lastPath: String? = null
        var lastMethod: String? = null
        var lastBody: String? = null
        override fun request(baseUrl: String, method: String, path: String, bearer: String?, body: String?, requestId: String?, internalToken: String?, actorId: String?): DownstreamResponse { lastBase = baseUrl; lastPath = path; lastMethod = method; lastBody = body; return response }
    }

    private class FakeAdminStore : AdminStore {
        var lastType: String? = null
        override fun createJob(type: String, actor: String, request: BulkJobRequest, correlation: String): AdminJobResponse { lastType = type; return sampleJob }
        override fun get(id: String): AdminJobResponse? = sampleJob.takeIf { id == it.id }
    }

    private companion object {
        val sampleJob = AdminJobResponse("job-1", "PRODUCT_PUBLISH", "PENDING", 2, 0, 0, 0, null, "2026-08-20T00:00:00Z", null)
    }
}
