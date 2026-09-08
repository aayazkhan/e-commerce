package com.ecommerce.platform.security

import com.ecommerce.platform.error.ApiException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionAuthTest {
    private val issuer = "issuer"
    private val audience = "audience"
    private val keyId = "kid-1"
    private val secret = "secret"
    private val verifier = HmacJwtAccessVerifier(issuer, audience, mapOf(keyId to secret))

    private fun io.ktor.server.application.Application.installErrorMapping() {
        install(StatusPages) {
            exception<ApiException> { call, cause ->
                call.respondText(cause.message, status = HttpStatusCode.fromValue(cause.statusCode))
            }
        }
    }

    @Test
    fun `requireAccess rejects a missing or malformed authorization header`() = testApplication {
        application { installErrorMapping() }
        routing {
            get("/protected") { call.respondText(call.requireAccess(verifier).subject) }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/protected").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/protected") { header("Authorization", "not-a-bearer-token") }.status,
        )
    }

    @Test
    fun `requireAccess returns the verified principal for a valid bearer token`() = testApplication {
        application { installErrorMapping() }
        routing {
            get("/protected") { call.respondText(call.requireAccess(verifier).subject) }
        }

        val response = client.get("/protected") { header("Authorization", "Bearer ${token()}") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("user-1", response.bodyAsText())
    }

    @Test
    fun `requirePermission allows a matching permission and rejects a missing one`() = testApplication {
        application { installErrorMapping() }
        routing {
            get("/needs-permission") { call.respondText(call.requirePermission(verifier, "ORDER_READ").subject) }
        }

        val allowed = client.get("/needs-permission") { header("Authorization", "Bearer ${token(permissions = "ORDER_READ")}") }
        assertEquals(HttpStatusCode.OK, allowed.status)

        val forbidden = client.get("/needs-permission") { header("Authorization", "Bearer ${token(permissions = "CART_READ")}") }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    }

    @Test
    fun `requirePermission grants privileged roles access regardless of listed permissions`() = testApplication {
        application { installErrorMapping() }
        routing {
            get("/needs-permission") { call.respondText(call.requirePermission(verifier, "ORDER_READ").subject) }
        }

        val response = client.get("/needs-permission") {
            header("Authorization", "Bearer ${token(roles = "ADMIN", permissions = "")}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `isPrivileged recognizes every admin-tier role and rejects ordinary ones`() {
        assertTrue(VerifiedAccessToken("u", setOf("SUPER_ADMIN"), emptySet(), "t").isPrivileged())
        assertTrue(VerifiedAccessToken("u", setOf("ADMIN"), emptySet(), "t").isPrivileged())
        assertTrue(VerifiedAccessToken("u", setOf("PLATFORM_ADMIN"), emptySet(), "t").isPrivileged())
        assertTrue(!VerifiedAccessToken("u", setOf("CUSTOMER"), emptySet(), "t").isPrivileged())
    }

    private fun token(roles: String = "CUSTOMER", permissions: String = "ORDER_READ"): String {
        val header = "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"$keyId\"}"
        val roleList = roles.split(',').filter { it.isNotBlank() }.joinToString(",") { "\"$it\"" }
        val permissionList = permissions.split(',').filter { it.isNotBlank() }.joinToString(",") { "\"$it\"" }
        val claims = "{\"subject\":\"user-1\",\"roles\":[$roleList],\"permissions\":[$permissionList],\"tokenId\":\"token-1\",\"issuedAt\":900,\"expiresAt\":2000000000,\"issuer\":\"$issuer\",\"audience\":\"$audience\"}"
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))
        val encodedClaims = encoder.encodeToString(claims.toByteArray(StandardCharsets.UTF_8))
        val input = "$encodedHeader.$encodedClaims"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))}"
    }
}
