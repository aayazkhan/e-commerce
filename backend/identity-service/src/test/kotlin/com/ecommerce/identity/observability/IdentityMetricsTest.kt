package com.ecommerce.identity.observability

import kotlin.test.Test
import kotlin.test.assertTrue

class IdentityMetricsTest {
    @Test
    fun `prometheus output reflects every counter after increments`() {
        val metrics = IdentityMetrics()
        metrics.registrationTotal.incrementAndGet()
        metrics.loginSuccessTotal.addAndGet(2)
        metrics.loginFailureTotal.incrementAndGet()
        metrics.tokenRefreshTotal.incrementAndGet()
        metrics.tokenRefreshFailureTotal.incrementAndGet()
        metrics.otpRequestsTotal.incrementAndGet()
        metrics.otpVerificationFailureTotal.incrementAndGet()
        metrics.passwordResetTotal.incrementAndGet()

        val output = metrics.prometheus()

        assertTrue(output.contains("identity_registration_total 1"))
        assertTrue(output.contains("identity_login_success_total 2"))
        assertTrue(output.contains("identity_login_failure_total 1"))
        assertTrue(output.contains("identity_token_refresh_total 1"))
        assertTrue(output.contains("identity_token_refresh_failure_total 1"))
        assertTrue(output.contains("identity_otp_requests_total 1"))
        assertTrue(output.contains("identity_otp_verification_failure_total 1"))
        assertTrue(output.contains("identity_password_reset_total 1"))
        assertTrue(output.contains("# TYPE identity_registration_total counter"))
    }
}
