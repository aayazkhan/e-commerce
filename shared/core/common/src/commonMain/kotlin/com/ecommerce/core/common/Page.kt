package com.ecommerce.core.common

/** Cursor-paginated collection, matching the backend's opaque-nextCursor convention (API.md). */
data class Page<T>(val items: List<T>, val nextCursor: String? = null) {
    val hasMore: Boolean get() = nextCursor != null
}
