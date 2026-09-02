package com.ecommerce.identity.domain

import java.time.Instant

enum class UserStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    LOCKED,
    DEACTIVATED,
    DELETED,
}

enum class OtpPurpose {
    PHONE_VERIFICATION,
    EMAIL_VERIFICATION,
    LOGIN,
    PASSWORD_RESET,
}

enum class VerificationPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
}

enum class AddressLabel {
    HOME,
    WORK,
    OTHER,
}

data class UserAccount(
    val id: String,
    val email: String?,
    val phone: String?,
    val status: UserStatus,
    val emailVerifiedAt: Instant?,
    val phoneVerifiedAt: Instant?,
    val roles: Set<String>,
    val permissions: Set<String>,
)

data class UserProfile(
    val userId: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String?,
    val gender: String?,
    val profileImageUrl: String?,
    val preferredLanguage: String,
    val preferredCurrency: String,
    val marketingEmail: Boolean,
    val marketingSms: Boolean,
    val marketingPush: Boolean,
)

data class UserAddress(
    val id: String,
    val userId: String,
    val label: AddressLabel,
    val recipientName: String,
    val phone: String,
    val line1: String,
    val line2: String?,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String,
    val latitude: Double?,
    val longitude: Double?,
    val isDefault: Boolean,
)

fun UserStatus.canTransitionTo(target: UserStatus): Boolean = when (this) {
    UserStatus.PENDING_VERIFICATION -> target in setOf(UserStatus.ACTIVE, UserStatus.DEACTIVATED, UserStatus.DELETED)
    UserStatus.ACTIVE -> target in setOf(UserStatus.SUSPENDED, UserStatus.LOCKED, UserStatus.DEACTIVATED, UserStatus.DELETED)
    UserStatus.SUSPENDED -> target in setOf(UserStatus.ACTIVE, UserStatus.DEACTIVATED, UserStatus.DELETED)
    UserStatus.LOCKED -> target in setOf(UserStatus.ACTIVE, UserStatus.DEACTIVATED, UserStatus.DELETED)
    UserStatus.DEACTIVATED -> target in setOf(UserStatus.ACTIVE, UserStatus.DELETED)
    UserStatus.DELETED -> false
}

fun normalizeEmail(value: String): String = value.trim().lowercase()

fun normalizePhone(value: String): String = value.filter { it.isDigit() || it == '+' }

fun validateEmail(value: String): Boolean = value.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

fun validatePassword(value: String): List<String> = buildList {
    if (value.length < 12) add("Password must be at least 12 characters")
    if (value.none(Char::isUpperCase)) add("Password must contain an uppercase letter")
    if (value.none(Char::isLowerCase)) add("Password must contain a lowercase letter")
    if (value.none(Char::isDigit)) add("Password must contain a number")
    if (value.none { !it.isLetterOrDigit() }) add("Password must contain a symbol")
}
