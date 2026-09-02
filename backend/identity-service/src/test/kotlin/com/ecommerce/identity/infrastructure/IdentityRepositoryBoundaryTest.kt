package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.domain.AddressLabel
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.identity.security.OAuthIdentity
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class IdentityRepositoryBoundaryTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `read methods return empty results without leaking persistence details`() {
        val repository = IdentityRepository(dataSource())

        assertNull(repository.findCredentials("user@example.com"))
        assertNull(repository.findAccountByProvider("GOOGLE", "subject-1"))
        assertNull(repository.account("user-1"))
        assertTrue(repository.sessions("user-1").isEmpty())
        assertTrue(repository.listAccounts(0).isEmpty())
        assertTrue(repository.listAddresses("user-1").isEmpty())
        assertTrue(repository.unpublishedOutbox(0).isEmpty())
    }

    @Test
    fun `registration creates account graph and maps duplicate constraint`() {
        val repository = IdentityRepository(dataSource { sql -> if (sql.contains("SELECT id, email")) listOf(accountRow("PENDING_VERIFICATION")) else emptyList() })
        val created = repository.register("User@Example.com", null, "hash", "User", "Example", "verify-hash", now) { "{\"userId\":\"$it\"}" }
        assertEquals(UserStatus.PENDING_VERIFICATION, created.account.status)
        assertEquals("user@example.com", created.account.email)

        val duplicate = IdentityRepository(dataSource(updateError = SQLException("duplicate", "23505")))
        val error = assertFailsWith<ApiException> { duplicate.register("user@example.com", null, "hash", "User", "Example", "verify-hash", now) { "{}" } }
        assertEquals(ErrorCode.CONFLICT, error.errorCode)
    }

    @Test
    fun `registration and provider linking preserve unexpected database errors`() {
        val registrationFailure = assertFailsWith<SQLException> {
            IdentityRepository(dataSource(updateError = SQLException("database unavailable", "08001")))
                .register("user@example.com", null, "hash", "User", "Example", "verify-hash", now) { "{}" }
        }
        assertEquals("08001", registrationFailure.sqlState)

        val providerFailure = assertFailsWith<SQLException> {
            IdentityRepository(dataSource(updateError = SQLException("database unavailable", "08001")))
                .linkProvider("user-1", "GOOGLE", "subject-1", now)
        }
        assertEquals("08001", providerFailure.sqlState)

        val unexpectedFailure = assertFailsWith<IllegalStateException> {
            IdentityRepository(dataSource(updateError = IllegalStateException("unexpected database wrapper")))
                .linkProvider("user-1", "GOOGLE", "subject-1", now)
        }
        assertEquals("unexpected database wrapper", unexpectedFailure.message)
    }

    @Test
    fun `provider lookup maps an existing identity and login failure ignores a missing account`() {
        val repository = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("SELECT user_id FROM user_auth_providers") -> listOf(mapOf<Any, Any?>("user_id" to "user-1"))
                sql.contains("SELECT id, email") -> listOf(accountRow())
                else -> emptyList()
            }
        })
        assertEquals("user-1", repository.findAccountByProvider("GOOGLE", "subject-1")!!.id)

        IdentityRepository(dataSource()).recordLoginFailure("missing", 3, 10, now)
    }

    @Test
    fun `login failure records both normal and locked attempts`() {
        val repository = IdentityRepository(dataSource { sql -> if (sql.contains("failed_login_attempts FROM users")) listOf(mapOf<Any, Any?>(1 to 0)) else emptyList() })
        repository.recordLoginFailure("user-1", maxAttempts = 3, lockMinutes = 10, now = now)
        repository.recordLoginFailure("user-1", maxAttempts = 1, lockMinutes = 10, now = now)
    }

    @Test
    fun `refresh rotation rejects missing expired and reused tokens`() {
        val missing = IdentityRepository(dataSource())
        assertAuthFailure { missing.rotateRefreshToken("old", "new", "ses-2", now, now.plusSeconds(100)) }

        val expired = IdentityRepository(dataSource { sql -> if (sql.contains("refresh_token_hash")) listOf(refreshRow(expiresAt = now.minusSeconds(1))) else emptyList() })
        assertAuthFailure { expired.rotateRefreshToken("old", "new", "ses-2", now, now.plusSeconds(100)) }

        val reused = IdentityRepository(dataSource { sql -> if (sql.contains("refresh_token_hash")) listOf(refreshRow(replacedBy = "ses-previous")) else emptyList() })
        assertAuthFailure { reused.rotateRefreshToken("old", "new", "ses-2", now, now.plusSeconds(100)) }

        val revoked = IdentityRepository(dataSource { sql -> if (sql.contains("refresh_token_hash")) listOf(refreshRow(revokedAt = now.minusSeconds(1))) else emptyList() })
        assertAuthFailure { revoked.rotateRefreshToken("old", "new", "ses-2", now, now.plusSeconds(100)) }

        val valid = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("refresh_token_hash") -> listOf(refreshRow())
                sql.contains("SELECT id, email") -> listOf(accountRow())
                else -> emptyList()
            }
        })
        val rotation = valid.rotateRefreshToken("old", "new", "ses-2", now, now.plusSeconds(100))
        assertEquals("ses-2", rotation.sessionId)
        assertEquals("user-1", rotation.account.id)

        val noExpiry = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("refresh_token_hash") -> listOf(refreshRow().minus("expires_at"))
                else -> emptyList()
            }
        })
        assertAuthFailure { noExpiry.rotateRefreshToken("old", "new", "ses-2", now, now.plusSeconds(100)) }
    }

    @Test
    fun `verification and OTP branches reject invalid challenges and accept a phone code`() {
        val missing = IdentityRepository(dataSource())
        assertChallengeFailure { missing.consumeVerificationToken("missing", "EMAIL_VERIFICATION", now) }
        assertChallengeFailure { missing.verifyOtp("missing", "code", 3, now, null, null) }
        assertChallengeFailure { missing.resetPassword("missing", "new-hash", now) }

        val consumed = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("verification_tokens") -> listOf(tokenRow(consumedAt = now.minusSeconds(1)))
                sql.contains("otp_challenges") -> listOf(otpRow(consumedAt = now.minusSeconds(1)))
                else -> emptyList()
            }
        })
        assertChallengeFailure { consumed.consumeVerificationToken("hash", "EMAIL_VERIFICATION", now) }
        assertChallengeFailure { consumed.verifyOtp("otp-1", "code", 3, now, null, null) }

        val consumedAndExpired = IdentityRepository(dataSource { sql -> if (sql.contains("verification_tokens")) listOf(tokenRow(consumedAt = now.minusSeconds(1), expiresAt = now)) else emptyList() })
        assertChallengeFailure { consumedAndExpired.consumeVerificationToken("hash", "EMAIL_VERIFICATION", now) }

        val expiredBeforeConsumption = IdentityRepository(dataSource { sql ->
            if (sql.contains("verification_tokens")) listOf(tokenRow(consumedAt = null, expiresAt = now.minusSeconds(1))) else emptyList()
        })
        assertChallengeFailure { expiredBeforeConsumption.consumeVerificationToken("hash", "EMAIL_VERIFICATION", now) }

        val wrongOtp = IdentityRepository(dataSource { sql -> if (sql.contains("otp_challenges")) listOf(otpRow(codeHash = "expected")) else emptyList() })
        assertChallengeFailure { wrongOtp.verifyOtp("otp-1", "wrong", 3, now, null, null) }

        val phone = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("otp_challenges") -> listOf(otpRow(userId = "user-1", purpose = "PHONE_VERIFICATION", codeHash = "expected"))
                else -> emptyList()
            }
        })
        val verification = phone.verifyOtp("otp-1", "expected", 3, now, null, null)
        assertEquals(OtpVerification("user-1", "PHONE_VERIFICATION"), verification)

        val nonEmail = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("verification_tokens") -> listOf(tokenRow())
                sql.contains("SELECT id, email") -> listOf(accountRow())
                else -> emptyList()
            }
        }).consumeVerificationToken("hash", "LOGIN", now)
        assertEquals(UserStatus.ACTIVE, nonEmail.status)

        assertChallengeFailure {
            IdentityRepository(dataSource { sql -> if (sql.contains("otp_challenges")) listOf(otpRow(userId = "user-1", expiresAt = now.minusSeconds(1))) else emptyList() })
                .verifyOtp("otp-1", "expected", 3, now, null, null)
        }
        assertChallengeFailure {
            IdentityRepository(dataSource { sql -> if (sql.contains("otp_challenges")) listOf(otpRow(userId = "user-1", attempts = 3)) else emptyList() })
                .verifyOtp("otp-1", "expected", 3, now, null, null)
        }
        assertChallengeFailure {
            IdentityRepository(dataSource { sql -> if (sql.contains("otp_challenges")) listOf(otpRow(userId = "user-1")) else emptyList() })
                .verifyOtp("otp-1", "expected", 3, now, "other-user", null)
        }
        assertChallengeFailure {
            IdentityRepository(dataSource { sql -> if (sql.contains("otp_challenges")) listOf(otpRow(userId = "user-1")) else emptyList() })
                .verifyOtp("otp-1", "expected", 3, now, null, "PHONE_VERIFICATION")
        }
        val nonPhone = IdentityRepository(dataSource { sql -> if (sql.contains("otp_challenges")) listOf(otpRow(userId = "user-1", purpose = "LOGIN", codeHash = "expected")) else emptyList() })
            .verifyOtp("otp-1", "expected", 3, now, "user-1", "LOGIN")
        assertEquals(OtpVerification("user-1", "LOGIN"), nonPhone)

        val anonymousPhone = IdentityRepository(dataSource { sql -> if (sql.contains("otp_challenges")) listOf(otpRow(userId = null, purpose = "PHONE_VERIFICATION", codeHash = "expected")) else emptyList() })
            .verifyOtp("otp-1", "expected", 3, now, null, "PHONE_VERIFICATION")
        assertEquals(OtpVerification(null, "PHONE_VERIFICATION"), anonymousPhone)
    }

    @Test
    fun `account status and profile and address failures map to domain errors`() {
        val current = IdentityRepository(dataSource { sql -> if (sql.contains("SELECT id, email")) listOf(accountRow()) else emptyList() })
        assertEquals(UserStatus.ACTIVE, current.setStatus("user-1", UserStatus.ACTIVE, "admin-1", "corr-1").status)

        val missingUpdates = IdentityRepository(dataSource(updateCount = 0))
        assertNotFound { missingUpdates.updateProfile("user-1", ProfileUpdate(null, null, null, null, null, null, null), now) }
        assertNotFound { missingUpdates.updateAddress("user-1", "address-1", addressInput(), now) }
        assertNotFound { missingUpdates.deleteAddress("user-1", "address-1", now) }
        assertNotFound { missingUpdates.setDefaultAddress("user-1", "address-1", now) }
        assertNotFound { missingUpdates.deactivate("user-1", now) }
        assertNotFound { missingUpdates.getProfile("user-1") }
        assertNotFound {
            IdentityRepository(dataSource()).createAddress("user-1", addressInput(), now)
        }
    }

    @Test
    fun `session, OAuth, phone, and address writes preserve returned domain values`() {
        val repository = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("SELECT * FROM user_addresses") -> listOf(addressRow())
                sql.contains("SELECT id, email") -> listOf(accountRow())
                else -> emptyList()
            }
        })
        val session = repository.createSession("user-1", "family-1", "refresh-hash", "device-1", "ANDROID", "1.0", null, now, now.plusSeconds(100))
        assertEquals("user-1", session.userId)
        assertEquals(now.plusSeconds(100), session.expiresAt)

        val address = repository.createAddress("user-1", addressInput().copy(latitude = 18.52, longitude = 73.85), now)
        assertEquals("address-1", address.id)
        assertEquals(AddressLabel.HOME, address.label)

        repository.createVerificationToken("user-1", "EMAIL_VERIFICATION", "token-hash", now, now.plusSeconds(100))
        repository.createOtp("otp-1", "user-1", "PHONE_VERIFICATION", "destination", "code", now, now.plusSeconds(100))
        repository.markPhoneVerified("user-1", now)
        repository.revokeSession("user-1", session.id, now)
        repository.revokeAllSessions("user-1", now)

        val oauth = repository.createOAuthAccount(OAuthIdentity("GOOGLE", "subject-1", "user@example.com", "User", "Example"), "hash", now) { "{}" }
        assertEquals("user@example.com", oauth.email)

        assertNotFound {
            IdentityRepository(dataSource()).createOAuthAccount(
                OAuthIdentity("GOOGLE", "missing-subject", "missing@example.com", "Missing", "User"),
                "hash",
                now,
            ) { "{}" }
        }
    }

    @Test
    fun `successful reads map sessions accounts profiles addresses roles and permissions`() {
        val repository = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("FROM user_sessions") -> listOf(sessionRow())
                sql.contains("SELECT id FROM users") -> listOf(mapOf<Any, Any?>(1 to "user-1"))
                sql.contains("SELECT * FROM user_addresses") -> listOf(addressRow())
                sql.contains("SELECT * FROM user_profiles") -> listOf(profileRow())
                sql.contains("SELECT id, email") -> listOf(accountRow())
                sql.contains("SELECT role") -> listOf(mapOf<Any, Any?>(1 to "CUSTOMER"))
                sql.contains("SELECT permission") -> listOf(mapOf<Any, Any?>(1 to "profile:read"))
                else -> emptyList()
            }
        })
        assertEquals("session-1", repository.sessions("user-1").single().id)
        assertEquals("user-1", repository.listAccounts(100).single().id)
        assertEquals("address-1", repository.listAddresses("user-1").single().id)
        val profile = repository.getProfile("user-1")
        assertEquals("User", profile.firstName)
        assertTrue(profile.marketingEmail)
        assertEquals(setOf("CUSTOMER"), repository.account("user-1")!!.roles)
    }

    @Test
    fun `verification reset and provider persistence cover successful and duplicate paths`() {
        val verified = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("verification_tokens") -> listOf(tokenRow())
                sql.contains("SELECT id, email") -> listOf(accountRow())
                else -> emptyList()
            }
        }).consumeVerificationToken("hash", "EMAIL_VERIFICATION", now)
        assertEquals(UserStatus.ACTIVE, verified.status)

        val reset = IdentityRepository(dataSource { sql -> if (sql.contains("verification_tokens")) listOf(tokenRow()) else emptyList() })
        reset.resetPassword("hash", "new-password-hash", now)

        IdentityRepository(dataSource()).linkProvider("user-1", "GOOGLE", "subject-1", now)
        val duplicate = assertFailsWith<ApiException> {
            IdentityRepository(dataSource(updateError = SQLException("duplicate", "23505"))).linkProvider("user-1", "GOOGLE", "subject-1", now)
        }
        assertEquals(ErrorCode.CONFLICT, duplicate.errorCode)

        val credentials = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("FROM users u JOIN user_credentials") -> listOf(credentialRow())
                sql.contains("SELECT role") -> listOf(mapOf<Any, Any?>(1 to "CUSTOMER"))
                else -> emptyList()
            }
        }).findCredentials("user@example.com")
        assertEquals("password-hash", credentials!!.passwordHash)
    }

    @Test
    fun `status address and outbox success paths preserve state and publication records`() {
        var accountReads = 0
        val repository = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("SELECT id, email") -> listOf(accountRow(if (++accountReads == 1) "ACTIVE" else "SUSPENDED"))
                sql.contains("SELECT * FROM user_addresses") -> listOf(addressRow())
                sql.contains("outbox_events") -> listOf(outboxRow())
                else -> emptyList()
            }
        })
        assertEquals(UserStatus.SUSPENDED, repository.setStatus("user-1", UserStatus.SUSPENDED, "admin", "corr").status)
        val created = repository.createAddress("user-1", addressInput().copy(isDefault = true), now)
        assertEquals("address-1", created.id)
        assertEquals("address-1", repository.updateAddress("user-1", "address-1", addressInput().copy(latitude = 18.52, longitude = 73.85, isDefault = true), now).id)
        assertEquals("address-1", repository.setDefaultAddress("user-1", "address-1", now).id)
        repository.deleteAddress("user-1", "address-1", now)
        val outbox = repository.unpublishedOutbox(10)
        assertEquals("User", outbox.single().aggregateType)
        repository.markOutboxPublished(outbox.map { it.id }, now)
        repository.markOutboxPublished(emptyList(), now)

        val reactivated = IdentityRepository(dataSource { sql -> if (sql.contains("SELECT id, email")) listOf(accountRow("SUSPENDED")) else emptyList() })
        assertEquals(UserStatus.SUSPENDED, reactivated.setStatus("user-1", UserStatus.ACTIVE, "admin", "corr").status)
    }

    @Test
    fun `profile and address mappers preserve nullable and coordinate fields`() {
        val repository = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("SELECT * FROM user_profiles") -> listOf(profileRow(nullable = true))
                sql.contains("SELECT * FROM user_addresses") -> listOf(addressRow(latitude = 18.52, longitude = 73.85))
                else -> emptyList()
            }
        })
        val profile = repository.getProfile("user-1")
        assertNull(profile.dateOfBirth)
        assertNull(profile.gender)
        assertNull(profile.profileImageUrl)
        assertEquals(18.52, repository.listAddresses("user-1").single().latitude)
        assertEquals(73.85, repository.listAddresses("user-1").single().longitude)

        val resetExpired = IdentityRepository(dataSource { sql -> if (sql.contains("verification_tokens")) listOf(tokenRow(expiresAt = now)) else emptyList() })
        assertChallengeFailure { resetExpired.resetPassword("hash", "new-hash", now) }
        val resetConsumed = IdentityRepository(dataSource { sql -> if (sql.contains("verification_tokens")) listOf(tokenRow(consumedAt = now.minusSeconds(1))) else emptyList() })
        assertChallengeFailure { resetConsumed.resetPassword("hash", "new-hash", now) }
    }

    @Test
    fun `login success profile update and deactivation execute successful persistence paths`() {
        val repository = IdentityRepository(dataSource { sql ->
            when {
                sql.contains("SELECT id, email") -> listOf(accountRow("LOCKED"))
                sql.contains("SELECT * FROM user_profiles") -> listOf(profileRow())
                else -> emptyList()
            }
        })

        val unlocked = repository.recordLoginSuccess("user-1", now)
        assertEquals(UserStatus.LOCKED, unlocked.status)

        val updated = repository.updateProfile(
            "user-1",
            ProfileUpdate("Updated", "Name", "hi", "INR", true, false, true),
            now,
        )
        assertEquals("User", updated.firstName)
        assertTrue(updated.marketingPush)

        repository.deactivate("user-1", now)
    }

    @Test
    fun `OTP and outbox transport records preserve optional values`() {
        val otp = OtpRecord(
            id = "otp-1",
            userId = null,
            purpose = "LOGIN",
            destinationHash = "destination-hash",
            codeHash = "code-hash",
            attempts = 2,
            expiresAt = now.plusSeconds(60),
            consumedAt = null,
        )
        assertNull(otp.userId)
        assertEquals(2, otp.attempts)
        assertNull(otp.consumedAt)
    }

    private fun assertAuthFailure(block: () -> Unit) {
        val error = assertFailsWith<ApiException> { block() }
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, error.errorCode)
    }

    private fun assertChallengeFailure(block: () -> Unit) {
        val error = assertFailsWith<ApiException> { block() }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun assertNotFound(block: () -> Unit) {
        val error = assertFailsWith<ApiException> { block() }
        assertEquals(ErrorCode.NOT_FOUND, error.errorCode)
    }

    private fun accountRow(status: String = "ACTIVE") = mapOf<Any, Any?>(
        "id" to "user-1",
        "email" to "user@example.com",
        "phone" to null,
        "status" to status,
        "email_verified_at" to Timestamp.from(now),
        "phone_verified_at" to null,
    )

    private fun refreshRow(expiresAt: Instant = now.plusSeconds(100), revokedAt: Instant? = null, replacedBy: String? = null) = mapOf<Any, Any?>(
        "id" to "ses-1",
        "user_id" to "user-1",
        "token_family" to "family-1",
        "revoked_at" to revokedAt?.let(Timestamp::from),
        "replaced_by" to replacedBy,
        "expires_at" to Timestamp.from(expiresAt),
    )

    private fun sessionRow() = mapOf<Any, Any?>(
        "id" to "session-1", "user_id" to "user-1", "device_id" to "device-1", "platform" to "ANDROID", "app_version" to "1.0",
        "created_at" to Timestamp.from(now), "last_active_at" to Timestamp.from(now), "expires_at" to Timestamp.from(now.plusSeconds(100)),
    )

    private fun profileRow(nullable: Boolean = false) = mapOf<Any, Any?>(
        "user_id" to "user-1", "first_name" to "User", "last_name" to "Example", "date_of_birth" to if (nullable) null else Date.valueOf("1990-01-01"),
        "gender" to if (nullable) null else "OTHER", "profile_image_url" to null, "preferred_language" to "en", "preferred_currency" to "INR",
        "marketing_email" to true, "marketing_sms" to false, "marketing_push" to true,
    )

    private fun credentialRow() = accountRow() + ("password_hash" to "password-hash")

    private fun outboxRow() = mapOf<Any, Any?>(
        "id" to "event-1", "aggregate_type" to "User", "aggregate_id" to "user-1", "event_type" to "UserRegistered",
        "schema_version" to 1, "occurred_at" to Timestamp.from(now), "correlation_id" to "corr", "payload_json" to "{\"userId\":\"user-1\"}",
    )

    private fun tokenRow(consumedAt: Instant? = null, expiresAt: Instant = now.plusSeconds(100)) = mapOf<Any, Any?>(
        "id" to "token-1",
        "user_id" to "user-1",
        "expires_at" to Timestamp.from(expiresAt),
        "consumed_at" to consumedAt?.let(Timestamp::from),
    )

    private fun otpRow(userId: String? = null, purpose: String = "LOGIN", codeHash: String = "code", attempts: Int = 0, expiresAt: Instant = now.plusSeconds(100), consumedAt: Instant? = null) = mapOf<Any, Any?>(
        "user_id" to userId,
        "purpose" to purpose,
        "code_hash" to codeHash,
        "attempts" to attempts,
        "expires_at" to Timestamp.from(expiresAt),
        "consumed_at" to consumedAt?.let(Timestamp::from),
    )

    private fun addressInput() = AddressInput(AddressLabel.HOME, "Customer", "+919999999999", "1 Main Street", null, "Pune", "MH", "411001", "IN", null, null, false)

    private fun addressRow(latitude: Double? = null, longitude: Double? = null) = mapOf<Any, Any?>(
        "id" to "address-1", "user_id" to "user-1", "label" to "HOME", "recipient_name" to "Customer", "phone" to "+919999999999", "line1" to "1 Main Street", "line2" to null, "city" to "Pune", "state" to "MH", "postal_code" to "411001", "country" to "IN", "latitude" to latitude, "longitude" to longitude, "is_default" to false,
    )

    private fun dataSource(updateCount: Int = 1, updateError: Throwable? = null, rows: (String) -> List<Map<Any, Any?>> = { emptyList() }): DataSource {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty(), rows, updateCount, updateError)
                "setAutoCommit", "rollback", "commit", "close" -> null
                "createArrayOf" -> null
                else -> defaultValue(method.returnType)
            }
        }) as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
            if (method.name == "getConnection") connection else defaultValue(method.returnType)
        }) as DataSource
    }

    private fun statement(sql: String, rows: (String) -> List<Map<Any, Any?>>, updateCount: Int, updateError: Throwable?): PreparedStatement = Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, _ ->
        when (method.name) {
            "executeQuery" -> resultSet(rows(sql))
            "executeUpdate" -> updateError?.let { throw it } ?: updateCount
            "executeBatch" -> intArrayOf()
            "setString", "setInt", "setLong", "setBoolean", "setTimestamp", "setNull", "setDouble", "addBatch", "close" -> null
            else -> defaultValue(method.returnType)
        }
    }) as PreparedStatement

    private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
        var index = -1
        var wasNull = false
        fun value(key: Any): Any? = rows.getOrNull(index)?.get(key).also { wasNull = it == null }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
            val key = args?.firstOrNull() ?: ""
            when (method.name) {
                "next" -> ++index < rows.size
                "getString" -> value(key)?.toString()
                "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                "getBoolean" -> value(key) as? Boolean ?: false
                "getTimestamp" -> when (val item = value(key)) { is Timestamp -> item; is Instant -> Timestamp.from(item); else -> null }
                "getDate" -> value(key) as? Date
                "getObject" -> value(key)
                "wasNull" -> wasNull
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as ResultSet
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0.0f
        else -> null
    }
}
