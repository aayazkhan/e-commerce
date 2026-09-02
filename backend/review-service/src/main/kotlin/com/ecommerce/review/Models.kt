package com.ecommerce.review

import kotlinx.serialization.Serializable

@Serializable enum class ReviewTarget { PRODUCT, SELLER }
@Serializable enum class ReviewStatus { SUBMITTED, PUBLISHED, HIDDEN, REJECTED, ARCHIVED }
@Serializable data class ReviewRequest(val targetType: ReviewTarget, val targetId: String, val orderId: String, val rating: Int, val title: String? = null, val body: String)
@Serializable data class ReviewResponse(val id: String, val userId: String, val targetType: ReviewTarget, val targetId: String, val orderId: String, val rating: Int, val title: String?, val body: String, val verifiedPurchase: Boolean, val status: ReviewStatus, val helpfulCount: Int, val createdAt: String)
@Serializable data class ReviewPage(val items: List<ReviewResponse>, val nextCursor: String?)
@Serializable data class ModerationRequest(val status: ReviewStatus, val reason: String? = null)
@Serializable data class ReportRequest(val reason: String)
@Serializable data class RatingAggregate(val targetType: ReviewTarget, val targetId: String, val count: Long, val average: Double)
