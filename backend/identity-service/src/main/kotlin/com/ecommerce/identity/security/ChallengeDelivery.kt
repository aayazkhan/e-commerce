package com.ecommerce.identity.security

import com.ecommerce.identity.config.ChallengeDeliveryConfig
import com.ecommerce.identity.domain.OtpPurpose
import com.ecommerce.identity.domain.VerificationPurpose
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface ChallengeDelivery {
    fun deliverOtp(userId: String?, destination: String, purpose: OtpPurpose, code: String)
    fun deliverVerification(userId: String, destination: String, purpose: VerificationPurpose, token: String)
}

class HttpChallengeDelivery(
    private val config: ChallengeDeliveryConfig,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : ChallengeDelivery {
    private val json = Json { encodeDefaults = true }

    override fun deliverOtp(userId: String?, destination: String, purpose: OtpPurpose, code: String) {
        post(ChallengeMessage("OTP", userId, destination, purpose.name, code))
    }

    override fun deliverVerification(userId: String, destination: String, purpose: VerificationPurpose, token: String) {
        post(ChallengeMessage("VERIFICATION", userId, destination, purpose.name, token))
    }

    private fun post(message: ChallengeMessage) {
        val url = config.url ?: throw unavailable()
        require(url.startsWith("https://") || url.startsWith("http://localhost")) {
            "Challenge delivery endpoint must use HTTPS outside localhost"
        }
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(message)))
        config.bearerToken?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        } catch (_: Exception) {
            throw unavailable()
        }
        if (response.statusCode() !in 200..299) throw unavailable()
    }

    private fun unavailable() = ApiException(
        errorCode = ErrorCode.DEPENDENCY_UNAVAILABLE,
        message = "Verification delivery is temporarily unavailable.",
        statusCode = 503,
        retryable = true,
    )
}

/**
 * Routes by destination shape: an "@"-containing destination is an email, sent via [email]
 * (Resend). Anything else is a phone number: sent via [sms] (MSG91) when configured, otherwise
 * via [smsFallback] -- normally the local challenge-stub.py webhook in dev (see
 * ops/local/run-identity.sh). [VerificationPurpose] (email verification / password reset) is
 * always email by construction, so it always goes through [email] directly.
 */
class MultiChannelChallengeDelivery(
    private val email: EmailProvider,
    private val smsFallback: ChallengeDelivery,
    private val sms: SmsProvider? = null,
) : ChallengeDelivery {
    override fun deliverOtp(userId: String?, destination: String, purpose: OtpPurpose, code: String) {
        if ("@" in destination) {
            email.send(destination, otpSubject(purpose), otpHtml(purpose, code))
        } else if (sms != null) {
            sms.sendOtp(destination, code)
        } else {
            smsFallback.deliverOtp(userId, destination, purpose, code)
        }
    }

    override fun deliverVerification(userId: String, destination: String, purpose: VerificationPurpose, token: String) {
        email.send(destination, verificationSubject(purpose), verificationHtml(purpose, token))
    }

    private fun otpSubject(purpose: OtpPurpose) = when (purpose) {
        OtpPurpose.LOGIN -> "Your login code"
        OtpPurpose.PASSWORD_RESET -> "Your password reset code"
        OtpPurpose.EMAIL_VERIFICATION -> "Verify your email"
        OtpPurpose.PHONE_VERIFICATION -> "Verify your phone"
    }

    private fun otpHtml(purpose: OtpPurpose, code: String) = """
        <p>Your one-time code is:</p>
        <p style="font-size:28px;font-weight:700;letter-spacing:4px;">$code</p>
        <p>This code expires shortly and can only be used once. If you didn't request this, you can ignore this email.</p>
    """.trimIndent()

    private fun verificationSubject(purpose: VerificationPurpose) = when (purpose) {
        VerificationPurpose.EMAIL_VERIFICATION -> "Verify your email address"
        VerificationPurpose.PASSWORD_RESET -> "Reset your password"
    }

    private fun verificationHtml(purpose: VerificationPurpose, token: String) = when (purpose) {
        VerificationPurpose.EMAIL_VERIFICATION -> """
            <p>Use the code below to verify your email address:</p>
            <p style="font-size:22px;font-weight:700;letter-spacing:2px;">$token</p>
            <p>If you didn't create an account, you can ignore this email.</p>
        """.trimIndent()
        VerificationPurpose.PASSWORD_RESET -> """
            <p>Use the code below to reset your password:</p>
            <p style="font-size:22px;font-weight:700;letter-spacing:2px;">$token</p>
            <p>If you didn't request a password reset, you can ignore this email.</p>
        """.trimIndent()
    }
}

@Serializable
private data class ChallengeMessage(
    val type: String,
    val userId: String?,
    val destination: String,
    val purpose: String,
    val secret: String,
)
