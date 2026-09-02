package com.ecommerce.promotion

import kotlinx.serialization.Serializable

@Serializable enum class PromotionType { PERCENTAGE, FIXED_AMOUNT, PRODUCT_DISCOUNT, CATEGORY_DISCOUNT, BUY_X_GET_Y, FREE_SHIPPING }
@Serializable enum class PromotionStatus { DRAFT, ACTIVE, PAUSED, EXPIRED, ARCHIVED }
@Serializable enum class StackPolicy { NO_STACK, BEST_DISCOUNT_ONLY, PRIORITY_BASED }
@Serializable enum class RedemptionStatus { RESERVED, COMMITTED, RELEASED, CANCELLED }

@Serializable
data class PromotionLine(val productId: String, val variantId: String, val categoryId: String? = null, val sellerId: String? = null, val quantity: Int, val unitPriceMinor: Long) {
    val lineTotalMinor: Long get() = quantity.toLong() * unitPriceMinor
}

@Serializable
data class PromotionCalculateRequest(val currency: String = "INR", val lines: List<PromotionLine>, val couponCode: String? = null, val promotionId: String? = null, val shippingMinor: Long = 0, val customerSegment: String = "DEFAULT")

@Serializable
data class PromotionQuote(val promotionId: String?, val couponCode: String?, val currency: String, val discountMinor: Long, val freeShipping: Boolean, val eligibleLineDiscounts: Map<String, Long> = emptyMap(), val reason: String? = null)

@Serializable
data class PromotionResponse(val id: String, val name: String, val type: PromotionType, val status: PromotionStatus, val startAt: String, val endAt: String?, val currency: String, val minOrderMinor: Long, val maxDiscountMinor: Long?, val percentageBps: Int?, val fixedAmountMinor: Long?, val productIds: List<String>, val categoryIds: List<String>, val sellerIds: List<String>, val usageLimit: Long?, val usageCount: Long, val perUserLimit: Long?, val stackPolicy: StackPolicy, val priority: Int, val version: Long, val customerSegments: List<String> = emptyList())

@Serializable
data class RedemptionResponse(val id: String, val promotionId: String, val couponCode: String?, val userId: String, val orderId: String?, val discountMinor: Long, val currency: String, val status: RedemptionStatus, val quote: PromotionQuote, val createdAt: String)

@Serializable
data class PromotionRequest(val name: String, val type: PromotionType, val status: PromotionStatus = PromotionStatus.DRAFT, val startAt: String, val endAt: String? = null, val currency: String = "INR", val minOrderMinor: Long = 0, val maxDiscountMinor: Long? = null, val percentageBps: Int? = null, val fixedAmountMinor: Long? = null, val buyQuantity: Int? = null, val getQuantity: Int? = null, val productIds: List<String> = emptyList(), val categoryIds: List<String> = emptyList(), val sellerIds: List<String> = emptyList(), val usageLimit: Long? = null, val perUserLimit: Long? = null, val stackPolicy: StackPolicy = StackPolicy.NO_STACK, val priority: Int = 0, val customerSegments: List<String> = emptyList())

@Serializable data class CouponRequest(val promotionId: String, val code: String, val usageLimit: Long? = null, val perUserLimit: Long? = null)
@Serializable data class CouponUpdateRequest(val status: String, val usageLimit: Long? = null, val perUserLimit: Long? = null)

fun percentageDiscountMinor(totalMinor: Long, percentageBps: Int): Long {
    require(totalMinor >= 0 && percentageBps in 0..10_000)
    return totalMinor * percentageBps / 10_000
}
