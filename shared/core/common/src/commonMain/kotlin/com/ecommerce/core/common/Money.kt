package com.ecommerce.core.common

import kotlinx.serialization.Serializable

/**
 * Mirrors the backend's wire format exactly (see API.md's Money convention):
 * amounts are always minor units (e.g. paise/cents), never floating point.
 */
@Serializable
data class Money(val amountMinor: Long, val currency: String)
