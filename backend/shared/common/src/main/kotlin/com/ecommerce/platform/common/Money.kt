package com.ecommerce.platform.common

import kotlinx.serialization.Serializable

@Serializable
data class Money(
    val amountMinor: Long,
    val currency: String,
) {
    init {
        require(currency.matches(Regex("[A-Z]{3}"))) { "Currency must be an ISO-4217 code" }
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add different currencies" }
        return copy(amountMinor = amountMinor + other.amountMinor)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Cannot subtract different currencies" }
        return copy(amountMinor = amountMinor - other.amountMinor)
    }
}
