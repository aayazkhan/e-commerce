package com.ecommerce.identity.http

import com.ecommerce.identity.application.IdentityService
import com.ecommerce.identity.application.SessionContext
import com.ecommerce.identity.security.JwtClaims
import com.ecommerce.identity.security.JwtService
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.platform.common.RequestMetadata
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.identityRoutes(service: IdentityService, jwtService: JwtService, internalServiceToken: String) {
    route("/api/v1/auth") {
        post("/register") {
            val account = service.register(call.receive<RegisterRequest>().toCommand(), call.metadata())
            call.respond(HttpStatusCode.Created, account.toResponse())
        }
        post("/login") {
            val request = call.receive<LoginRequest>()
            call.respond(service.login(request.toCommand(call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim()), call.metadata()).toResponse())
        }
        post("/refresh") {
            val request = call.receive<RefreshRequest>()
            call.respond(service.refresh(request.refreshToken, sessionContextFromCall(call)).toResponse())
        }
        post("/oauth/{provider}") {
            val provider = call.parameters.requireValue("provider")
            val request = call.receive<OAuthRequest>()
            val session = SessionContext(request.deviceId, request.platform, request.appVersion, call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim())
            call.respond(service.oauthLogin(provider, request.credential, session, call.metadata()).toResponse())
        }
        post("/logout") {
            val user = call.requireUser(jwtService)
            val request = call.receive<LogoutRequest>()
            service.logout(user.subject, request.sessionId, request.allSessions)
            call.respond(MessageResponse("Session revoked."))
        }
        get("/sessions") {
            val user = call.requireUser(jwtService)
            call.respond(service.sessions(user.subject).map { it.toResponse() })
        }
        delete("/sessions/{sessionId}") {
            val user = call.requireUser(jwtService)
            service.logout(user.subject, call.parameters.requireValue("sessionId"), false)
            call.respond(MessageResponse("Session revoked."))
        }
        delete("/sessions") {
            val user = call.requireUser(jwtService)
            service.logout(user.subject, null, true)
            call.respond(MessageResponse("All sessions revoked."))
        }
        post("/email/verify") {
            val account = service.verifyEmail(call.receive<VerificationRequest>().token)
            call.respond(account.toResponse())
        }
        post("/email/resend") {
            val user = call.requireUser(jwtService)
            service.resendEmailVerification(user.subject)
            call.respond(MessageResponse("If verification is required, a new message will be sent."))
        }
        post("/password/forgot") {
            service.requestPasswordReset(call.receive<PasswordForgotRequest>().identifier, call.metadata())
            call.respond(MessageResponse("If the account exists, recovery instructions will be sent."))
        }
        post("/password/reset") {
            val request = call.receive<PasswordResetRequest>()
            service.resetPassword(request.token, request.password)
            call.respond(MessageResponse("Password reset successfully."))
        }
        post("/otp/request") {
            call.respond(ChallengeResponse(service.requestOtp(call.receive<OtpRequest>().toCommand())))
        }
        post("/otp/verify") {
            val request = call.receive<OtpVerifyRequest>()
            val verification = service.verifyOtp(request.challengeId, request.code)
            val auth = if (verification.purpose == com.ecommerce.identity.domain.OtpPurpose.LOGIN.name) service.loginWithOtp(verification, sessionContextFromCall(call), call.metadata()).toResponse() else null
            call.respond(OtpVerificationResponse(verification.userId, auth))
        }
        post("/phone/resend") {
            val user = call.requireUser(jwtService)
            val request = call.receive<OtpRequest>()
            val challengeId = service.requestOtp(request.copy(userId = user.subject, purpose = com.ecommerce.identity.domain.OtpPurpose.PHONE_VERIFICATION.name).toCommand())
            call.respond(ChallengeResponse(challengeId))
        }
        post("/phone/verify") {
            val user = call.requireUser(jwtService)
            val request = call.receive<OtpVerifyRequest>()
            service.verifyOtp(request.challengeId, request.code, user.subject, com.ecommerce.identity.domain.OtpPurpose.PHONE_VERIFICATION)
            call.respond(MessageResponse("Phone verified."))
        }
    }

    route("/api/v1/users/me") {
        get {
            val user = call.requireUser(jwtService)
            call.respond(service.accountFromClaims(user).toResponse())
        }
        patch {
            val user = call.requireUser(jwtService)
            call.respond(service.updateProfile(user.subject, call.receive<ProfileRequest>().toCommand()).toResponse())
        }
        delete {
            val user = call.requireUser(jwtService)
            service.deactivate(user.subject)
            call.respond(MessageResponse("Account deactivated."))
        }
    }

    route("/api/v1/profile") {
        get {
            val user = call.requireUser(jwtService)
            call.respond(service.profile(user.subject).toResponse())
        }
        patch {
            val user = call.requireUser(jwtService)
            call.respond(service.updateProfile(user.subject, call.receive<ProfileRequest>().toCommand()).toResponse())
        }
    }

    route("/api/v1/addresses") {
        get {
            val user = call.requireUser(jwtService)
            call.respond(service.addresses(user.subject).map { it.toResponse() })
        }
        post {
            val user = call.requireUser(jwtService)
            call.respond(HttpStatusCode.Created, service.createAddress(user.subject, call.receive<AddressRequest>().toCommand()).toResponse())
        }
        patch("/{addressId}") {
            val user = call.requireUser(jwtService)
            call.respond(service.updateAddress(user.subject, call.parameters.requireValue("addressId"), call.receive<AddressRequest>().toCommand()).toResponse())
        }
        delete("/{addressId}") {
            val user = call.requireUser(jwtService)
            service.deleteAddress(user.subject, call.parameters.requireValue("addressId"))
            call.respond(MessageResponse("Address deleted."))
        }
        post("/{addressId}/default") {
            val user = call.requireUser(jwtService)
            call.respond(service.setDefaultAddress(user.subject, call.parameters.requireValue("addressId")).toResponse())
        }
    }

    route("/api/v1/admin/users") {
        get { call.requireAdminPermission(jwtService, "ADMIN_USER_READ"); call.respond(service.adminUsers(call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).map { it.toResponse() }) }
        get("/{userId}") { call.requireAdminPermission(jwtService, "ADMIN_USER_READ"); call.respond(service.adminUser(call.parameters.requireValue("userId")).toResponse()) }
        patch("/{userId}/status") { val principal = call.requireAdminPermission(jwtService, "ADMIN_USER_UPDATE"); val status = parseAdminStatus(call.receive<AdminUserStatusRequest>().status); call.respond(service.adminSetStatus(call.parameters.requireValue("userId"), status, principal.subject, call.metadata().requestId).toResponse()) }
        post("/{userId}/suspend") { val principal = call.requireAdminPermission(jwtService, "ADMIN_USER_UPDATE"); call.respond(service.adminSetStatus(call.parameters.requireValue("userId"), UserStatus.SUSPENDED, principal.subject, call.metadata().requestId).toResponse()) }
        post("/{userId}/restore") { val principal = call.requireAdminPermission(jwtService, "ADMIN_USER_UPDATE"); call.respond(service.adminSetStatus(call.parameters.requireValue("userId"), UserStatus.ACTIVE, principal.subject, call.metadata().requestId).toResponse()) }
    }

    // Service-to-service only (X-Internal-Service-Token), never reachable through the public
    // gateway -- see ServiceRoutes.kt's own comment on why /api/v1/internal paths are absent
    // from the routable prefix list. Used by seller-service when an admin approves a seller
    // application, to grant the SELLER role so the newly-active seller can actually call
    // catalog-service's product endpoints (which gate on role, not on a seller-service record).
    route("/api/v1/internal/users/{userId}/roles") {
        post {
            call.requireInternal(internalServiceToken)
            val role = call.receive<GrantRoleRequest>().role
            call.respond(service.grantRole(call.parameters.requireValue("userId"), role).toResponse())
        }
    }
}

private fun ApplicationCall.requireInternal(expected: String) {
    if (expected.isBlank() || request.headers["X-Internal-Service-Token"] != expected) {
        throw ApiException(ErrorCode.FORBIDDEN, "Internal service authentication failed.", 403)
    }
}

private fun ApplicationCall.requireUser(jwtService: JwtService): JwtClaims {
    val value = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
    if (value.isNullOrBlank()) throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401)
    return jwtService.verifyAccessToken(value)
}

private fun ApplicationCall.requireAdminPermission(jwtService: JwtService, permission: String): JwtClaims {
    val principal = requireUser(jwtService)
    if (principal.roles.none { it in setOf("SUPER_ADMIN", "ADMIN", "PLATFORM_ADMIN") } && permission !in principal.permissions) throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403)
    return principal
}

private fun parseAdminStatus(value: String): UserStatus = runCatching { UserStatus.valueOf(value.uppercase()) }.getOrElse { throw ApiException(ErrorCode.VALIDATION_ERROR, "Invalid user status.", 400) }

private fun ApplicationCall.metadata() = RequestMetadata(
    requestId = request.headers[HttpHeaders.XRequestId] ?: "identity-${java.util.UUID.randomUUID()}",
    traceId = request.headers["traceparent"],
)

private fun io.ktor.http.Parameters.requireValue(name: String): String = get(name)?.takeIf { it.isNotBlank() } ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Missing path parameter: $name", 400)

private fun sessionContextFromCall(call: ApplicationCall) = SessionContext(
    deviceId = null,
    platform = null,
    appVersion = null,
    ipAddress = call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim(),
)
