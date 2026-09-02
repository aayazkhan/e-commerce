package com.ecommerce.recommendation

import kotlinx.serialization.Serializable

@Serializable enum class RecommendationType { RECENTLY_VIEWED, FREQUENTLY_BOUGHT_TOGETHER, SIMILAR, TRENDING, PERSONALIZED }
@Serializable data class BehaviorRequest(val eventType: String, val userId: String? = null, val sessionId: String? = null, val productId: String? = null, val query: String? = null)
@Serializable data class RecommendationResponse(val type: RecommendationType, val productIds: List<String>, val source: String, val cached: Boolean = false)
