package com.ecommerce.platform.redis

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class CachePolicy(
    val ttl: Duration,
    val staleWhileRevalidate: Duration = 0.minutes,
    val jitterPercent: Int = 10,
) {
    init {
        require(ttl.isPositive()) { "Cache TTL must be positive" }
        require(staleWhileRevalidate >= Duration.ZERO) { "Stale window cannot be negative" }
        require(jitterPercent in 0..50) { "Cache jitter must be between 0 and 50 percent" }
    }
}

object CachePolicies {
    val productDetail = CachePolicy(ttl = 5.minutes, staleWhileRevalidate = 2.minutes)
    val listing = CachePolicy(ttl = 1.minutes, staleWhileRevalidate = 30.seconds)
}
