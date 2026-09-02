package com.ecommerce.analytics

import kotlinx.serialization.Serializable

@Serializable data class MetricSummary(val from: String? = null, val to: String? = null, val events: Long, val views: Long, val searches: Long, val carts: Long, val orders: Long, val paidOrders: Long, val checkouts: Long, val revenueMinor: Long, val conversionRate: Double, val cartAbandonmentRate: Double)
@Serializable data class ReplayRequest(val from: String? = null, val to: String? = null)
@Serializable data class ReplayResponse(val runId: String, val eventsSeen: Int, val idempotent: Boolean = true)
