package com.ecommerce.platform.common

import kotlinx.serialization.Serializable

@Serializable
data class CursorPageRequest(
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "Page size must be between 1 and $MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT = 24
        const val MAX_LIMIT = 100
    }
}

@Serializable
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
)
