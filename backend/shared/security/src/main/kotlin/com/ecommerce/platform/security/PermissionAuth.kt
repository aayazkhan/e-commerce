package com.ecommerce.platform.security

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

fun ApplicationCall.requireAccess(verifier: HmacJwtAccessVerifier): VerifiedAccessToken {
    val token = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
        ?: throw ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401)
    return verifier.verify(token)
}

fun ApplicationCall.requirePermission(verifier: HmacJwtAccessVerifier, permission: String): VerifiedAccessToken {
    val principal = requireAccess(verifier)
    if (!principal.isPrivileged() && permission !in principal.permissions) {
        throw ApiException(ErrorCode.FORBIDDEN, "You do not have permission for this operation.", 403)
    }
    return principal
}

fun VerifiedAccessToken.isPrivileged(): Boolean = roles.any { it in setOf("SUPER_ADMIN", "ADMIN", "PLATFORM_ADMIN") }
