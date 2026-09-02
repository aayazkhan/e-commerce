package com.ecommerce.notification

import com.ecommerce.platform.error.ApiError
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.security.HmacJwtAccessVerifier
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

class NotificationRoutesTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val verifier = HmacJwtAccessVerifier("issuer", "audience", mapOf("kid-1" to "secret"))

    @Test
    fun `authenticated notification routes preserve user ownership and payloads`() = testApplication {
        val store = FakeNotificationStore()
        application { installRoutes(store) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/notifications/preferences").status)
        val user = token()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/notifications/preferences") { auth(user) }.status)
        val preference = PreferenceRequest(false, true, false, true, "22:00", "07:00", "Asia/Kolkata", "hi-IN")
        val preferenceResponse = client.put("/api/v1/notifications/preferences") { auth(user); contentType(ContentType.Application.Json); setBody(json.encodeToString(preference)) }
        assertEquals(HttpStatusCode.OK, preferenceResponse.status)
        assertEquals(preference, store.savedPreference)

        val device = client.post("/api/v1/notifications/devices") { auth(user); contentType(ContentType.Application.Json); setBody(json.encodeToString(DeviceRequest("ANDROID", "device-token"))) }
        assertEquals(HttpStatusCode.Created, device.status)
        assertEquals("user-1", store.deviceUser)

        val inApp = client.get("/api/v1/notifications/in-app?limit=8") { auth(user) }
        assertEquals(HttpStatusCode.OK, inApp.status)
        assertEquals(8, store.inAppLimit)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/notifications/in-app") { auth(user) }.status)
        assertEquals(50, store.inAppLimit)
        assertEquals(HttpStatusCode.NoContent, client.patch("/api/v1/notifications/in-app/message-1/read") { auth(user) }.status)
        assertEquals("message-1", store.readId)

        val webhook = client.post("/api/v1/providers/webhook") { contentType(ContentType.Application.Json); setBody(json.encodeToString(WebhookRequest("provider-1", "delivered"))) }
        assertEquals(HttpStatusCode.Accepted, webhook.status)
        assertEquals("DELIVERED", store.webhookStatus)
    }

    @Test
    fun `template route enforces permission and maps provider errors`() = testApplication {
        val store = FakeNotificationStore()
        application { installRoutes(store) }
        val body = json.encodeToString(TemplateRequest("order.status", "en-IN", "Order", "Body"))

        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/admin/notification-templates") { auth(token()); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.NoContent, client.put("/api/v1/admin/notification-templates") { auth(token("ADMIN_NOTIFICATION_TEMPLATE")); contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(body, store.templateBody)

        store.failure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "notification store unavailable", 503, retryable = true)
        val failed = client.get("/api/v1/notifications/preferences") { auth(token()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        assertTrue(failed.bodyAsText().contains("DEPENDENCY_UNAVAILABLE"))
    }

    @Test
    fun `privileged notification role bypasses the template permission list`() = testApplication {
        val store = FakeNotificationStore()
        application { installRoutes(store) }
        val body = json.encodeToString(TemplateRequest("order.status", "en-IN", "Order", "Body"))

        for (role in listOf("ADMIN", "SUPER_ADMIN", "SUPPORT")) {
            val response = client.put("/api/v1/admin/notification-templates") {
                auth(tokenWithRole(role))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
        assertEquals(body, store.templateBody)
    }

    private fun io.ktor.server.application.Application.installRoutes(store: NotificationStore) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.statusCode), ApiError(error.errorCode, error.message, "request-1", error.fieldViolations, error.retryable)) }
            exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorCode.INTERNAL_ERROR, "unexpected", "request-1")) }
        }
        routing { configureNotificationRoutes(store, verifier) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(value: String) = header(HttpHeaders.Authorization, "Bearer $value")

    private fun token(vararg permissions: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"user-1\",\"roles\":[],\"permissions\":[${permissions.joinToString { "\"$it\"" }}],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private fun tokenWithRole(role: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"kid-1\"}".toByteArray(StandardCharsets.UTF_8))
        val claims = encoder.encodeToString("{\"subject\":\"user-1\",\"roles\":[\"$role\"],\"permissions\":[],\"tokenId\":\"token-1\",\"issuedAt\":1700000000,\"expiresAt\":2000000000,\"issuer\":\"issuer\",\"audience\":\"audience\"}".toByteArray(StandardCharsets.UTF_8))
        val input = "$header.$claims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }

    private class FakeNotificationStore : NotificationStore {
        var savedPreference: PreferenceRequest? = null
        var deviceUser: String? = null
        var inAppLimit: Int? = null
        var readId: String? = null
        var webhookStatus: String? = null
        var templateBody: String? = null
        var failure: ApiException? = null
        override fun getPreferences(userId: String): PreferenceRequest { failure?.let { throw it }; return savedPreference ?: PreferenceRequest() }
        override fun preferences(userId: String, preferences: PreferenceRequest): Int { savedPreference = preferences; return 1 }
        override fun device(userId: String, device: DeviceRequest): Int { deviceUser = userId; return 1 }
        override fun listInApp(userId: String, limit: Int): List<InAppResponse> { inAppLimit = limit; return listOf(InAppResponse("message-1", "Order", "Ready", false, "2026-08-21T00:00:00Z")) }
        override fun markRead(userId: String, id: String): Int { readId = id; return 1 }
        override fun template(template: TemplateRequest): Int { templateBody = Json { encodeDefaults = true; explicitNulls = false }.encodeToString(template); return 1 }
        override fun updateWebhook(messageId: String, status: String): Int { webhookStatus = status.uppercase(); return 1 }
    }
}
