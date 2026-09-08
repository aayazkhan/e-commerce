package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.domain.AddressLabel
import com.ecommerce.identity.domain.UserAccount
import com.ecommerce.identity.domain.UserAddress
import com.ecommerce.identity.domain.UserProfile
import com.ecommerce.identity.domain.UserStatus
import com.ecommerce.identity.security.OAuthIdentity
import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class RegistrationRecord(
    val account: UserAccount,
)

data class CredentialRecord(
    val account: UserAccount,
    val passwordHash: String,
)

data class SessionRecord(
    val id: String,
    val userId: String,
    val deviceId: String?,
    val platform: String?,
    val appVersion: String?,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val expiresAt: Instant,
)

data class RotationRecord(
    val account: UserAccount,
    val sessionId: String,
)

data class OtpRecord(
    val id: String,
    val userId: String?,
    val purpose: String,
    val destinationHash: String,
    val codeHash: String,
    val attempts: Int,
    val expiresAt: Instant,
    val consumedAt: Instant?,
)

data class OtpVerification(
    val userId: String?,
    val purpose: String,
)

data class OutboxRecord(
    val id: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val correlationId: String,
    val payloadJson: String,
)

class IdentityRepository(private val dataSource: DataSource) : IdentityGateway, IdentityOutboxStore {
    override fun register(
        email: String?,
        phone: String?,
        passwordHash: String,
        firstName: String,
        lastName: String,
        verificationTokenHash: String,
        now: Instant,
        eventPayload: (String) -> String,
    ): RegistrationRecord = transaction { connection ->
        val userId = CommerceId.new("usr").value
        try {
            connection.prepareStatement(
                """
                INSERT INTO users (id, email, email_normalized, phone, phone_normalized, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING_VERIFICATION', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, email)
                statement.setString(3, email)
                statement.setString(4, phone)
                statement.setString(5, phone)
                statement.setTimestamp(6, now.timestamp())
                statement.setTimestamp(7, now.timestamp())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO user_credentials (user_id, password_hash, password_changed_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, passwordHash)
                statement.setTimestamp(3, now.timestamp())
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO user_roles (user_id, role) VALUES (?, 'CUSTOMER')").use { statement ->
                statement.setString(1, userId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO user_profiles (user_id, first_name, last_name, updated_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, firstName)
                statement.setString(3, lastName)
                statement.setTimestamp(4, now.timestamp())
                statement.executeUpdate()
            }
            val tokenId = CommerceId.new("verify").value
            connection.prepareStatement(
                """
                INSERT INTO verification_tokens (id, user_id, purpose, token_hash, created_at, expires_at)
                VALUES (?, ?, 'EMAIL_VERIFICATION', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tokenId)
                statement.setString(2, userId)
                statement.setString(3, verificationTokenHash)
                statement.setTimestamp(4, now.timestamp())
                statement.setTimestamp(5, now.plusSeconds(86_400).timestamp())
                statement.executeUpdate()
            }
            insertOutbox(connection, "User", userId, "UserRegistered", now, userId, eventPayload(userId))
            insertSecurityEvent(connection, userId, "REGISTRATION", now, null, null)
            RegistrationRecord(account = getAccount(connection, userId))
        } catch (error: Exception) {
            if (error.sqlState() == "23505") {
                throw ApiException(ErrorCode.CONFLICT, "An account with those credentials already exists.", 409)
            }
            throw error
        }
    }

    override fun findCredentials(identifier: String): CredentialRecord? = withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT u.id, u.email, u.phone, u.status, u.email_verified_at, u.phone_verified_at, c.password_hash
            FROM users u JOIN user_credentials c ON c.user_id = u.id
            WHERE u.email_normalized = ? OR u.phone_normalized = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, identifier)
            statement.setString(2, identifier)
            statement.executeQuery().use { result ->
                if (!result.next()) return@withConnection null
                val account = getAccount(connection, result.getString("id"), result)
                CredentialRecord(account, result.getString("password_hash"))
            }
        }
    }

    override fun findAccountByProvider(provider: String, subject: String): UserAccount? = withConnection { connection ->
        connection.prepareStatement("SELECT user_id FROM user_auth_providers WHERE provider = ? AND subject = ?").use { statement ->
            statement.setString(1, provider); statement.setString(2, subject)
            statement.executeQuery().use { result -> if (!result.next()) null else getAccount(connection, result.getString("user_id")) }
        }
    }

    override fun linkProvider(userId: String, provider: String, subject: String, now: Instant) = transaction { connection ->
        try {
            connection.prepareStatement("INSERT INTO user_auth_providers (user_id, provider, subject, created_at) VALUES (?, ?, ?, ?)").use { statement -> statement.setString(1, userId); statement.setString(2, provider); statement.setString(3, subject); statement.setTimestamp(4, now.timestamp()); statement.executeUpdate() }
        } catch (error: Exception) {
            if (error.sqlState() == "23505") throw ApiException(ErrorCode.CONFLICT, "That OAuth identity is already linked.", 409)
            throw error
        }
    }

    override fun createOAuthAccount(identity: OAuthIdentity, passwordHash: String, now: Instant, eventPayload: (String) -> String): UserAccount = transaction { connection ->
        val userId = CommerceId.new("usr").value
        connection.prepareStatement("INSERT INTO users (id, email, email_normalized, status, email_verified_at, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)").use { statement -> statement.setString(1, userId); statement.setString(2, identity.email); statement.setString(3, identity.email); statement.setTimestamp(4, now.timestamp()); statement.setTimestamp(5, now.timestamp()); statement.setTimestamp(6, now.timestamp()); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO user_credentials (user_id, password_hash, password_changed_at) VALUES (?, ?, ?)").use { statement -> statement.setString(1, userId); statement.setString(2, passwordHash); statement.setTimestamp(3, now.timestamp()); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO user_roles (user_id, role) VALUES (?, 'CUSTOMER')").use { statement -> statement.setString(1, userId); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO user_profiles (user_id, first_name, last_name, updated_at) VALUES (?, ?, ?, ?)").use { statement -> statement.setString(1, userId); statement.setString(2, identity.firstName.take(100)); statement.setString(3, identity.lastName.take(100)); statement.setTimestamp(4, now.timestamp()); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO user_auth_providers (user_id, provider, subject, created_at) VALUES (?, ?, ?, ?)").use { statement -> statement.setString(1, userId); statement.setString(2, identity.provider); statement.setString(3, identity.subject); statement.setTimestamp(4, now.timestamp()); statement.executeUpdate() }
        insertOutbox(connection, "User", userId, "UserRegistered", now, userId, eventPayload(userId))
        getAccount(connection, userId)
    }

    override fun recordLoginFailure(userId: String, maxAttempts: Int, lockMinutes: Long, now: Instant) = transaction { connection ->
        connection.prepareStatement("SELECT failed_login_attempts FROM users WHERE id = ? FOR UPDATE").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@transaction
                val attempts = result.getInt(1) + 1
                val locked = attempts >= maxAttempts
                connection.prepareStatement(
                    "UPDATE users SET failed_login_attempts = ?, locked_until = ?, status = CASE WHEN ? THEN 'LOCKED' ELSE status END, updated_at = ? WHERE id = ?",
                ).use { update ->
                    update.setInt(1, attempts)
                    if (locked) update.setTimestamp(2, now.plusSeconds(lockMinutes * 60).timestamp()) else update.setTimestamp(2, null)
                    update.setBoolean(3, locked)
                    update.setTimestamp(4, now.timestamp())
                    update.setString(5, userId)
                    update.executeUpdate()
                }
                insertSecurityEvent(connection, userId, if (locked) "ACCOUNT_LOCKED" else "LOGIN_FAILURE", now, null, null)
            }
        }
    }

    override fun recordLoginSuccess(userId: String, now: Instant): UserAccount = transaction { connection ->
        connection.prepareStatement(
            "UPDATE users SET failed_login_attempts = 0, locked_until = NULL, status = CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE status END, updated_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setTimestamp(1, now.timestamp())
            statement.setString(2, userId)
            statement.executeUpdate()
        }
        insertSecurityEvent(connection, userId, "LOGIN_SUCCESS", now, null, null)
        getAccount(connection, userId)
    }

    override fun createSession(
        userId: String,
        tokenFamily: String,
        refreshTokenHash: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
        ipHash: String?,
        now: Instant,
        expiresAt: Instant,
    ): SessionRecord = transaction { connection ->
        val id = CommerceId.new("ses").value
        connection.prepareStatement(
            """
            INSERT INTO user_sessions (id, user_id, token_family, refresh_token_hash, device_id, platform, app_version, ip_hash, created_at, last_active_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, userId)
            statement.setString(3, tokenFamily)
            statement.setString(4, refreshTokenHash)
            statement.setString(5, deviceId)
            statement.setString(6, platform)
            statement.setString(7, appVersion)
            statement.setString(8, ipHash)
            statement.setTimestamp(9, now.timestamp())
            statement.setTimestamp(10, now.timestamp())
            statement.setTimestamp(11, expiresAt.timestamp())
            statement.executeUpdate()
        }
        insertSecurityEvent(connection, userId, "SESSION_CREATED", now, null, null)
        SessionRecord(id, userId, deviceId, platform, appVersion, now, now, expiresAt)
    }

    override fun rotateRefreshToken(
        refreshTokenHash: String,
        newRefreshTokenHash: String,
        newSessionId: String,
        now: Instant,
        newExpiresAt: Instant,
    ): RotationRecord = transaction { connection ->
        val row = connection.prepareStatement(
            "SELECT id, user_id, token_family, revoked_at, replaced_by, expires_at FROM user_sessions WHERE refresh_token_hash = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, refreshTokenHash)
            statement.executeQuery().use { result ->
                if (!result.next()) throw authFailure()
                RefreshRow(
                    id = result.getString("id"),
                    userId = result.getString("user_id"),
                    tokenFamily = result.getString("token_family"),
                    revokedAt = result.instant("revoked_at"),
                    replacedBy = result.getString("replaced_by"),
                    expiresAt = result.instant("expires_at") ?: now,
                )
            }
        }
        if (row.replacedBy != null || row.revokedAt != null) {
            revokeTokenFamily(connection, row.tokenFamily, now, reuseDetected = true)
            throw authFailure()
        }
        if (row.expiresAt <= now) throw authFailure()
        connection.prepareStatement(
            "UPDATE user_sessions SET revoked_at = ?, replaced_by = ?, last_active_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setTimestamp(1, now.timestamp())
            statement.setString(2, newSessionId)
            statement.setTimestamp(3, now.timestamp())
            statement.setString(4, row.id)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO user_sessions (id, user_id, token_family, refresh_token_hash, created_at, last_active_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, newSessionId)
            statement.setString(2, row.userId)
            statement.setString(3, row.tokenFamily)
            statement.setString(4, newRefreshTokenHash)
            statement.setTimestamp(5, now.timestamp())
            statement.setTimestamp(6, now.timestamp())
            statement.setTimestamp(7, newExpiresAt.timestamp())
            statement.executeUpdate()
        }
        insertSecurityEvent(connection, row.userId, "TOKEN_REFRESH", now, null, null)
        RotationRecord(getAccount(connection, row.userId), newSessionId)
    }

    override fun revokeSession(userId: String, sessionId: String, now: Instant) = transaction { connection ->
        connection.prepareStatement("UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ? AND id = ?").use { statement ->
            statement.setTimestamp(1, now.timestamp())
            statement.setString(2, userId)
            statement.setString(3, sessionId)
            statement.executeUpdate()
        }
        insertSecurityEvent(connection, userId, "SESSION_REVOKED", now, null, null)
    }

    override fun revokeAllSessions(userId: String, now: Instant) = transaction { connection ->
        connection.prepareStatement("UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?").use { statement ->
            statement.setTimestamp(1, now.timestamp())
            statement.setString(2, userId)
            statement.executeUpdate()
        }
        insertSecurityEvent(connection, userId, "ALL_SESSIONS_REVOKED", now, null, null)
    }

    override fun sessions(userId: String): List<SessionRecord> = withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, user_id, device_id, platform, app_version, created_at, last_active_at, expires_at FROM user_sessions WHERE user_id = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP ORDER BY last_active_at DESC",
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(
                        SessionRecord(
                            id = result.getString("id"),
                            userId = result.getString("user_id"),
                            deviceId = result.getString("device_id"),
                            platform = result.getString("platform"),
                            appVersion = result.getString("app_version"),
                            createdAt = result.instant("created_at")!!,
                            lastActiveAt = result.instant("last_active_at")!!,
                            expiresAt = result.instant("expires_at")!!,
                        ),
                    )
                }
            }
        }
    }

    override fun createVerificationToken(userId: String, purpose: String, tokenHash: String, now: Instant, expiresAt: Instant) = transaction { connection ->
        val id = CommerceId.new("vt").value
        connection.prepareStatement(
            "INSERT INTO verification_tokens (id, user_id, purpose, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, userId)
            statement.setString(3, purpose)
            statement.setString(4, tokenHash)
            statement.setTimestamp(5, now.timestamp())
            statement.setTimestamp(6, expiresAt.timestamp())
            statement.executeUpdate()
        }
    }

    override fun consumeVerificationToken(tokenHash: String, purpose: String, now: Instant): UserAccount = transaction { connection ->
        val row = connection.prepareStatement(
            "SELECT id, user_id, expires_at, consumed_at FROM verification_tokens WHERE token_hash = ? AND purpose = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.setString(2, purpose)
            statement.executeQuery().use { result ->
                if (!result.next()) throw invalidChallenge()
                TokenRow(result.getString("id"), result.getString("user_id"), result.instant("expires_at")!!, result.instant("consumed_at"))
            }
        }
        if (row.consumedAt != null || row.expiresAt <= now) throw invalidChallenge()
        connection.prepareStatement("UPDATE verification_tokens SET consumed_at = ? WHERE id = ?").use { statement ->
            statement.setTimestamp(1, now.timestamp())
            statement.setString(2, row.id)
            statement.executeUpdate()
        }
        if (purpose == "EMAIL_VERIFICATION") {
            connection.prepareStatement("UPDATE users SET email_verified_at = ?, status = CASE WHEN status = 'PENDING_VERIFICATION' THEN 'ACTIVE' ELSE status END, updated_at = ? WHERE id = ?").use { statement ->
                statement.setTimestamp(1, now.timestamp())
                statement.setTimestamp(2, now.timestamp())
                statement.setString(3, row.userId)
                statement.executeUpdate()
            }
        }
        insertSecurityEvent(connection, row.userId, "VERIFICATION_COMPLETED", now, null, null)
        getAccount(connection, row.userId)
    }

    override fun createOtp(id: String, userId: String?, purpose: String, destinationHash: String, codeHash: String, now: Instant, expiresAt: Instant) = transaction { connection ->
        connection.prepareStatement(
            "INSERT INTO otp_challenges (id, user_id, purpose, destination_hash, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, userId)
            statement.setString(3, purpose)
            statement.setString(4, destinationHash)
            statement.setString(5, codeHash)
            statement.setTimestamp(6, now.timestamp())
            statement.setTimestamp(7, expiresAt.timestamp())
            statement.executeUpdate()
        }
    }

    override fun verifyOtp(id: String, codeHash: String, maxAttempts: Int, now: Instant, expectedUserId: String?, expectedPurpose: String?): OtpVerification = transaction { connection ->
        val row = connection.prepareStatement("SELECT user_id, purpose, code_hash, attempts, expires_at, consumed_at FROM otp_challenges WHERE id = ? FOR UPDATE").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                if (!result.next()) throw invalidChallenge()
                OtpRow(result.getString("user_id"), result.getString("purpose"), result.getString("code_hash"), result.getInt("attempts"), result.instant("expires_at")!!, result.instant("consumed_at"))
            }
        }
        if (row.consumedAt != null || row.expiresAt <= now || row.attempts >= maxAttempts) throw invalidChallenge()
        if (expectedUserId != null && row.userId != expectedUserId) throw invalidChallenge()
        if (expectedPurpose != null && row.purpose != expectedPurpose) throw invalidChallenge()
        if (row.codeHash != codeHash) {
            connection.prepareStatement("UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
            throw invalidChallenge()
        }
        connection.prepareStatement("UPDATE otp_challenges SET consumed_at = ? WHERE id = ?").use { statement ->
            statement.setTimestamp(1, now.timestamp())
            statement.setString(2, id)
            statement.executeUpdate()
        }
        if (row.userId != null && row.purpose == "PHONE_VERIFICATION") markPhoneVerified(connection, row.userId, now)
        OtpVerification(row.userId, row.purpose)
    }

    override fun resetPassword(tokenHash: String, passwordHash: String, now: Instant) = transaction { connection ->
        val row = connection.prepareStatement("SELECT id, user_id, expires_at, consumed_at FROM verification_tokens WHERE token_hash = ? AND purpose = 'PASSWORD_RESET' FOR UPDATE").use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { result ->
                if (!result.next()) throw invalidChallenge()
                TokenRow(result.getString("id"), result.getString("user_id"), result.instant("expires_at")!!, result.instant("consumed_at"))
            }
        }
        if (row.consumedAt != null || row.expiresAt <= now) throw invalidChallenge()
        connection.prepareStatement("UPDATE user_credentials SET password_hash = ?, password_changed_at = ? WHERE user_id = ?").use { statement ->
            statement.setString(1, passwordHash)
            statement.setTimestamp(2, now.timestamp())
            statement.setString(3, row.userId)
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE verification_tokens SET consumed_at = ? WHERE id = ?").use { statement ->
            statement.setTimestamp(1, now.timestamp())
            statement.setString(2, row.id)
            statement.executeUpdate()
        }
        revokeUserSessions(connection, row.userId, now)
        insertSecurityEvent(connection, row.userId, "PASSWORD_RESET", now, null, null)
    }

    override fun account(userId: String): UserAccount? = withConnection { connection ->
        connection.prepareStatement("SELECT id FROM users WHERE id = ?").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { result -> if (!result.next()) null else getAccount(connection, userId) }
        }
    }

    override fun listAccounts(limit: Int): List<UserAccount> = withConnection { connection ->
        connection.prepareStatement("SELECT id FROM users ORDER BY created_at DESC LIMIT ?").use { statement ->
            statement.setInt(1, limit.coerceIn(1, 100))
            statement.executeQuery().use { result -> buildList { while (result.next()) add(getAccount(connection, result.getString(1))) } }
        }
    }

    override fun setStatus(userId: String, status: UserStatus, actorId: String, correlationId: String): UserAccount = transaction { connection ->
        val current = getAccount(connection, userId)
        if (current.status == status) return@transaction current
        connection.prepareStatement("UPDATE users SET status=?,deactivated_at=CASE WHEN ?='DEACTIVATED' THEN now() ELSE deactivated_at END,updated_at=now() WHERE id=?").use { statement ->
            statement.setString(1, status.name); statement.setString(2, status.name); statement.setString(3, userId); statement.executeUpdate()
        }
        if (status != UserStatus.ACTIVE) revokeUserSessions(connection, userId, Instant.now())
        insertSecurityEvent(connection, userId, "USER_STATUS_CHANGED", Instant.now(), correlationId, null)
        insertOutbox(connection, "User", userId, "User${status.name.replaceFirstChar { it.uppercase() }}", Instant.now(), correlationId, "{\"userId\":\"$userId\",\"actorId\":\"$actorId\",\"status\":\"${status.name}\"}")
        getAccount(connection, userId)
    }

    override fun updateProfile(userId: String, profile: ProfileUpdate, now: Instant): UserProfile = transaction { connection ->
        connection.prepareStatement(
            "UPDATE user_profiles SET first_name = COALESCE(?, first_name), last_name = COALESCE(?, last_name), preferred_language = COALESCE(?, preferred_language), preferred_currency = COALESCE(?, preferred_currency), marketing_email = COALESCE(?, marketing_email), marketing_sms = COALESCE(?, marketing_sms), marketing_push = COALESCE(?, marketing_push), updated_at = ? WHERE user_id = ?",
        ).use { statement ->
            statement.setString(1, profile.firstName)
            statement.setString(2, profile.lastName)
            statement.setString(3, profile.preferredLanguage)
            statement.setString(4, profile.preferredCurrency)
            profile.marketingEmail?.let { statement.setBoolean(5, it) } ?: statement.setNull(5, java.sql.Types.BOOLEAN)
            profile.marketingSms?.let { statement.setBoolean(6, it) } ?: statement.setNull(6, java.sql.Types.BOOLEAN)
            profile.marketingPush?.let { statement.setBoolean(7, it) } ?: statement.setNull(7, java.sql.Types.BOOLEAN)
            statement.setTimestamp(8, now.timestamp())
            statement.setString(9, userId)
            if (statement.executeUpdate() == 0) throw ApiException(ErrorCode.NOT_FOUND, "Profile was not found.", 404)
        }
        insertSecurityEvent(connection, userId, "PROFILE_UPDATED", now, null, null)
        getProfile(connection, userId)
    }

    override fun getProfile(userId: String): UserProfile = withConnection { connection ->
        connection.prepareStatement("SELECT * FROM user_profiles WHERE user_id = ?").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ApiException(ErrorCode.NOT_FOUND, "Profile was not found.", 404)
                profile(result)
            }
        }
    }

    override fun listAddresses(userId: String): List<UserAddress> = withConnection { connection ->
        connection.prepareStatement("SELECT * FROM user_addresses WHERE user_id = ? ORDER BY is_default DESC, updated_at DESC").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(address(result)) } }
        }
    }

    override fun createAddress(userId: String, input: AddressInput, now: Instant): UserAddress = transaction { connection ->
        val id = CommerceId.new("addr").value
        if (input.isDefault) clearDefault(connection, userId)
        connection.prepareStatement(
            "INSERT INTO user_addresses (id, user_id, label, recipient_name, phone, line1, line2, city, state, postal_code, country, latitude, longitude, is_default, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id); statement.setString(2, userId); statement.setString(3, input.label.name); statement.setString(4, input.recipientName); statement.setString(5, input.phone); statement.setString(6, input.line1); statement.setString(7, input.line2); statement.setString(8, input.city); statement.setString(9, input.state); statement.setString(10, input.postalCode); statement.setString(11, input.country); input.latitude?.let { statement.setDouble(12, it) } ?: statement.setNull(12, java.sql.Types.DOUBLE); input.longitude?.let { statement.setDouble(13, it) } ?: statement.setNull(13, java.sql.Types.DOUBLE); statement.setBoolean(14, input.isDefault); statement.setTimestamp(15, now.timestamp()); statement.setTimestamp(16, now.timestamp()); statement.executeUpdate()
        }
        insertSecurityEvent(connection, userId, "ADDRESS_CREATED", now, null, null)
        getAddress(connection, userId, id)
    }

    override fun updateAddress(userId: String, addressId: String, input: AddressInput, now: Instant): UserAddress = transaction { connection ->
        if (input.isDefault) clearDefault(connection, userId)
        connection.prepareStatement(
            "UPDATE user_addresses SET label = ?, recipient_name = ?, phone = ?, line1 = ?, line2 = ?, city = ?, state = ?, postal_code = ?, country = ?, latitude = ?, longitude = ?, is_default = ?, updated_at = ? WHERE id = ? AND user_id = ?",
        ).use { statement ->
            statement.setString(1, input.label.name); statement.setString(2, input.recipientName); statement.setString(3, input.phone); statement.setString(4, input.line1); statement.setString(5, input.line2); statement.setString(6, input.city); statement.setString(7, input.state); statement.setString(8, input.postalCode); statement.setString(9, input.country); input.latitude?.let { statement.setDouble(10, it) } ?: statement.setNull(10, java.sql.Types.DOUBLE); input.longitude?.let { statement.setDouble(11, it) } ?: statement.setNull(11, java.sql.Types.DOUBLE); statement.setBoolean(12, input.isDefault); statement.setTimestamp(13, now.timestamp()); statement.setString(14, addressId); statement.setString(15, userId); if (statement.executeUpdate() == 0) throw ApiException(ErrorCode.NOT_FOUND, "Address was not found.", 404)
        }
        getAddress(connection, userId, addressId)
    }

    override fun deleteAddress(userId: String, addressId: String, now: Instant) = transaction { connection ->
        connection.prepareStatement("DELETE FROM user_addresses WHERE id = ? AND user_id = ?").use { statement ->
            statement.setString(1, addressId); statement.setString(2, userId); if (statement.executeUpdate() == 0) throw ApiException(ErrorCode.NOT_FOUND, "Address was not found.", 404)
        }
        insertSecurityEvent(connection, userId, "ADDRESS_DELETED", now, null, null)
    }

    override fun setDefaultAddress(userId: String, addressId: String, now: Instant): UserAddress = transaction { connection ->
        clearDefault(connection, userId)
        connection.prepareStatement("UPDATE user_addresses SET is_default = TRUE, updated_at = ? WHERE id = ? AND user_id = ?").use { statement ->
            statement.setTimestamp(1, now.timestamp()); statement.setString(2, addressId); statement.setString(3, userId); if (statement.executeUpdate() == 0) throw ApiException(ErrorCode.NOT_FOUND, "Address was not found.", 404)
        }
        getAddress(connection, userId, addressId)
    }

    override fun deactivate(userId: String, now: Instant) = transaction { connection ->
        connection.prepareStatement("UPDATE users SET status = 'DEACTIVATED', deactivated_at = ?, updated_at = ? WHERE id = ? AND status <> 'DELETED'").use { statement ->
            statement.setTimestamp(1, now.timestamp()); statement.setTimestamp(2, now.timestamp()); statement.setString(3, userId); if (statement.executeUpdate() == 0) throw ApiException(ErrorCode.NOT_FOUND, "User was not found.", 404)
        }
        revokeUserSessions(connection, userId, now)
        insertSecurityEvent(connection, userId, "USER_DEACTIVATED", now, null, null)
    }

    fun markPhoneVerified(userId: String, now: Instant) = transaction { connection ->
        markPhoneVerified(connection, userId, now)
        insertSecurityEvent(connection, userId, "PHONE_VERIFIED", now, null, null)
    }

    override fun unpublishedOutbox(limit: Int): List<OutboxRecord> = withConnection { connection ->
        connection.prepareStatement("SELECT id, aggregate_type, aggregate_id, event_type, schema_version, occurred_at, correlation_id, payload_json FROM outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(
                        OutboxRecord(
                            id = result.getString("id"),
                            aggregateType = result.getString("aggregate_type"),
                            aggregateId = result.getString("aggregate_id"),
                            eventType = result.getString("event_type"),
                            schemaVersion = result.getInt("schema_version"),
                            occurredAt = result.instant("occurred_at")!!,
                            correlationId = result.getString("correlation_id"),
                            payloadJson = result.getString("payload_json"),
                        ),
                    )
                }
            }
        }
    }

    override fun markOutboxPublished(ids: Collection<String>, publishedAt: Instant) {
        transaction { connection ->
        if (ids.isEmpty()) return@transaction
        connection.prepareStatement("UPDATE outbox_events SET published_at = ? WHERE id = ANY (?)").use { statement ->
            statement.setTimestamp(1, publishedAt.timestamp())
            statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray()))
            statement.executeUpdate()
        }
    }
    }

    private fun getAccount(connection: Connection, userId: String, result: ResultSet? = null): UserAccount {
        // Build the return value while the ResultSet is still open -- returning a ResultSet out of
        // its own `.use {}` block (the previous shape here) hands back an already-closed resource.
        if (result != null) {
            return UserAccount(result.getString("id"), result.getString("email"), result.getString("phone"), UserStatus.valueOf(result.getString("status")), result.instant("email_verified_at"), result.instant("phone_verified_at"), roles(connection, userId), permissions(connection, userId))
        }
        return connection.prepareStatement("SELECT id, email, phone, status, email_verified_at, phone_verified_at FROM users WHERE id = ?").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) throw ApiException(ErrorCode.NOT_FOUND, "User was not found.", 404)
                UserAccount(rs.getString("id"), rs.getString("email"), rs.getString("phone"), UserStatus.valueOf(rs.getString("status")), rs.instant("email_verified_at"), rs.instant("phone_verified_at"), roles(connection, userId), permissions(connection, userId))
            }
        }
    }

    private fun roles(connection: Connection, userId: String): Set<String> = connection.prepareStatement("SELECT role FROM user_roles WHERE user_id = ?").use { statement -> statement.setString(1, userId); statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getString(1)) } } }

    private fun permissions(connection: Connection, userId: String): Set<String> = connection.prepareStatement("SELECT permission FROM user_permissions WHERE user_id = ?").use { statement -> statement.setString(1, userId); statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getString(1)) } } }

    private fun getProfile(connection: Connection, userId: String): UserProfile = connection.prepareStatement("SELECT * FROM user_profiles WHERE user_id = ?").use { statement -> statement.setString(1, userId); statement.executeQuery().use { result -> if (!result.next()) throw ApiException(ErrorCode.NOT_FOUND, "Profile was not found.", 404) else profile(result) } }

    private fun getAddress(connection: Connection, userId: String, addressId: String): UserAddress = connection.prepareStatement("SELECT * FROM user_addresses WHERE id = ? AND user_id = ?").use { statement -> statement.setString(1, addressId); statement.setString(2, userId); statement.executeQuery().use { result -> if (!result.next()) throw ApiException(ErrorCode.NOT_FOUND, "Address was not found.", 404) else address(result) } }

    private fun profile(result: ResultSet) = UserProfile(result.getString("user_id"), result.getString("first_name"), result.getString("last_name"), result.getDate("date_of_birth")?.toLocalDate()?.toString(), result.getString("gender"), result.getString("profile_image_url"), result.getString("preferred_language"), result.getString("preferred_currency"), result.getBoolean("marketing_email"), result.getBoolean("marketing_sms"), result.getBoolean("marketing_push"))

    private fun address(result: ResultSet) = UserAddress(result.getString("id"), result.getString("user_id"), AddressLabel.valueOf(result.getString("label")), result.getString("recipient_name"), result.getString("phone"), result.getString("line1"), result.getString("line2"), result.getString("city"), result.getString("state"), result.getString("postal_code"), result.getString("country"), result.getObject("latitude")?.toString()?.toDouble(), result.getObject("longitude")?.toString()?.toDouble(), result.getBoolean("is_default"))

    private fun clearDefault(connection: Connection, userId: String) = connection.prepareStatement("UPDATE user_addresses SET is_default = FALSE WHERE user_id = ? AND is_default = TRUE").use { statement -> statement.setString(1, userId); statement.executeUpdate() }

    private fun revokeTokenFamily(connection: Connection, family: String, now: Instant, reuseDetected: Boolean) = connection.prepareStatement("UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, ?), reuse_detected_at = CASE WHEN ? THEN COALESCE(reuse_detected_at, ?) ELSE reuse_detected_at END WHERE token_family = ?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setBoolean(2, reuseDetected); statement.setTimestamp(3, now.timestamp()); statement.setString(4, family); statement.executeUpdate() }

    private fun revokeUserSessions(connection: Connection, userId: String, now: Instant) = connection.prepareStatement("UPDATE user_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setString(2, userId); statement.executeUpdate() }

    private fun insertOutbox(connection: Connection, aggregateType: String, aggregateId: String, eventType: String, now: Instant, correlationId: String, payload: String) = connection.prepareStatement("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, schema_version, occurred_at, correlation_id, payload_json) VALUES (?, ?, ?, ?, 1, ?, ?, ?)").use { statement -> statement.setString(1, CommerceId.new("evt").value); statement.setString(2, aggregateType); statement.setString(3, aggregateId); statement.setString(4, eventType); statement.setTimestamp(5, now.timestamp()); statement.setString(6, correlationId); statement.setString(7, payload); statement.executeUpdate() }

    private fun insertSecurityEvent(connection: Connection, userId: String?, eventType: String, now: Instant, requestId: String?, traceId: String?) = connection.prepareStatement("INSERT INTO security_events (id, user_id, event_type, request_id, trace_id, occurred_at, metadata_json) VALUES (?, ?, ?, ?, ?, ?, '{}')").use { statement -> statement.setString(1, CommerceId.new("sec").value); statement.setString(2, userId); statement.setString(3, eventType); statement.setString(4, requestId); statement.setString(5, traceId); statement.setTimestamp(6, now.timestamp()); statement.executeUpdate() }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Exception) { connection.rollback(); throw error } }

    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    private data class RefreshRow(val id: String, val userId: String, val tokenFamily: String, val revokedAt: Instant?, val replacedBy: String?, val expiresAt: Instant)
    private data class TokenRow(val id: String, val userId: String, val expiresAt: Instant, val consumedAt: Instant?)
    private data class OtpRow(val userId: String?, val purpose: String, val codeHash: String, val attempts: Int, val expiresAt: Instant, val consumedAt: Instant?)

    private fun markPhoneVerified(connection: Connection, userId: String, now: Instant) = connection.prepareStatement("UPDATE users SET phone_verified_at = ?, status = CASE WHEN status = 'PENDING_VERIFICATION' THEN 'ACTIVE' ELSE status END, updated_at = ? WHERE id = ?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setTimestamp(2, now.timestamp()); statement.setString(3, userId); statement.executeUpdate() }
}

data class ProfileUpdate(val firstName: String?, val lastName: String?, val preferredLanguage: String?, val preferredCurrency: String?, val marketingEmail: Boolean?, val marketingSms: Boolean?, val marketingPush: Boolean?)

data class AddressInput(val label: AddressLabel, val recipientName: String, val phone: String, val line1: String, val line2: String?, val city: String, val state: String, val postalCode: String, val country: String, val latitude: Double?, val longitude: Double?, val isDefault: Boolean)

private fun Instant.timestamp(): Timestamp = Timestamp.from(this)
private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
private fun Throwable.sqlState(): String? = (this as? java.sql.SQLException)?.sqlState
private fun authFailure() = ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", 401)
private fun invalidChallenge() = ApiException(ErrorCode.VALIDATION_ERROR, "The verification challenge is invalid or expired.", 400)
