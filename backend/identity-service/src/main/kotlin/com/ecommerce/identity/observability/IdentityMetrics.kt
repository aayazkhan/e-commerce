package com.ecommerce.identity.observability

import java.util.concurrent.atomic.AtomicLong

class IdentityMetrics {
    val registrationTotal = AtomicLong()
    val loginSuccessTotal = AtomicLong()
    val loginFailureTotal = AtomicLong()
    val tokenRefreshTotal = AtomicLong()
    val tokenRefreshFailureTotal = AtomicLong()
    val otpRequestsTotal = AtomicLong()
    val otpVerificationFailureTotal = AtomicLong()
    val passwordResetTotal = AtomicLong()

    fun prometheus(): String = buildString {
        gauge("identity_registration_total", registrationTotal.get())
        gauge("identity_login_success_total", loginSuccessTotal.get())
        gauge("identity_login_failure_total", loginFailureTotal.get())
        gauge("identity_token_refresh_total", tokenRefreshTotal.get())
        gauge("identity_token_refresh_failure_total", tokenRefreshFailureTotal.get())
        gauge("identity_otp_requests_total", otpRequestsTotal.get())
        gauge("identity_otp_verification_failure_total", otpVerificationFailureTotal.get())
        gauge("identity_password_reset_total", passwordResetTotal.get())
    }

    private fun StringBuilder.gauge(name: String, value: Long) {
        append("# TYPE ").append(name).append(" counter\n")
        append(name).append(' ').append(value).append('\n')
    }
}
