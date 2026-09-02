package com.ecommerce.order

import kotlinx.serialization.Serializable

@Serializable
enum class OrderStatus {
    CREATED, VALIDATING, INVENTORY_RESERVED, PAYMENT_PENDING, PAYMENT_PROCESSING, PAID, CONFIRMED, PROCESSING, SHIPPED, OUT_FOR_DELIVERY, DELIVERED,
    CANCEL_REQUESTED, CANCELLED, RETURN_REQUESTED, RETURN_APPROVED, RETURN_PICKUP, RETURNED, REFUND_PENDING, REFUNDED, FAILED
}

private val transitions = mapOf(
    OrderStatus.CREATED to setOf(OrderStatus.VALIDATING, OrderStatus.INVENTORY_RESERVED, OrderStatus.CANCELLED, OrderStatus.FAILED),
    OrderStatus.VALIDATING to setOf(OrderStatus.INVENTORY_RESERVED, OrderStatus.CANCELLED, OrderStatus.FAILED),
    OrderStatus.INVENTORY_RESERVED to setOf(OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED, OrderStatus.FAILED),
    OrderStatus.PAYMENT_PENDING to setOf(OrderStatus.PAYMENT_PROCESSING, OrderStatus.CANCELLED, OrderStatus.FAILED),
    OrderStatus.PAYMENT_PROCESSING to setOf(OrderStatus.PAID, OrderStatus.FAILED, OrderStatus.CANCEL_REQUESTED),
    OrderStatus.PAID to setOf(OrderStatus.CONFIRMED, OrderStatus.CANCEL_REQUESTED, OrderStatus.REFUND_PENDING, OrderStatus.FAILED),
    OrderStatus.CONFIRMED to setOf(OrderStatus.PROCESSING, OrderStatus.CANCEL_REQUESTED, OrderStatus.REFUND_PENDING),
    OrderStatus.PROCESSING to setOf(OrderStatus.SHIPPED, OrderStatus.CANCEL_REQUESTED),
    OrderStatus.SHIPPED to setOf(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.RETURN_REQUESTED),
    OrderStatus.OUT_FOR_DELIVERY to setOf(OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED),
    OrderStatus.DELIVERED to setOf(OrderStatus.RETURN_REQUESTED),
    OrderStatus.CANCEL_REQUESTED to setOf(OrderStatus.CANCELLED, OrderStatus.FAILED),
    OrderStatus.RETURN_REQUESTED to setOf(OrderStatus.RETURN_APPROVED, OrderStatus.CANCELLED),
    OrderStatus.RETURN_APPROVED to setOf(OrderStatus.RETURN_PICKUP),
    OrderStatus.RETURN_PICKUP to setOf(OrderStatus.RETURNED),
    OrderStatus.RETURNED to setOf(OrderStatus.REFUND_PENDING),
    OrderStatus.REFUND_PENDING to setOf(OrderStatus.REFUNDED, OrderStatus.FAILED),
)

fun assertOrderTransition(from: OrderStatus, to: OrderStatus) {
    if (from != to && to !in transitions.getOrDefault(from, emptySet())) throw IllegalStateException("Order cannot transition from $from to $to")
}

@Serializable
data class OrderItemSnapshot(
    val productId: String,
    val variantId: String,
    val productName: String,
    val sku: String? = null,
    val sellerId: String? = null,
    val quantity: Int,
    val unitPriceMinor: Long,
    val taxMinor: Long,
    val discountMinor: Long,
    val lineTotalMinor: Long,
    val currency: String,
    val priceVersion: String = "",
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class AddressSnapshot(val addressId: String, val recipientName: String, val phone: String, val line1: String, val line2: String? = null, val city: String, val state: String, val postalCode: String, val country: String)

@Serializable
data class PromotionSnapshot(val promotionId: String? = null, val couponCode: String? = null, val discountMinor: Long = 0, val allocation: Map<String, Long> = emptyMap())

@Serializable
data class OrderCreateRequest(
    val checkoutId: String,
    val reservationId: String? = null,
    val items: List<OrderItemSnapshot>,
    val shippingAddress: AddressSnapshot,
    val billingAddress: AddressSnapshot,
    val subtotalMinor: Long,
    val itemDiscountMinor: Long,
    val promotionDiscountMinor: Long,
    val shippingMinor: Long,
    val taxMinor: Long,
    val totalMinor: Long,
    val currency: String,
    val promotion: PromotionSnapshot? = null,
    val initialStatus: OrderStatus = OrderStatus.INVENTORY_RESERVED,
)

@Serializable
data class OrderResponse(
    val id: String,
    val userId: String,
    val checkoutId: String,
    val reservationId: String?,
    val status: OrderStatus,
    val subtotalMinor: Long,
    val itemDiscountMinor: Long,
    val promotionDiscountMinor: Long,
    val shippingMinor: Long,
    val taxMinor: Long,
    val totalMinor: Long,
    val currency: String,
    val items: List<OrderItemSnapshot>,
    val shippingAddress: AddressSnapshot,
    val billingAddress: AddressSnapshot,
    val promotion: PromotionSnapshot?,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable data class OrderStatusRequest(val status: OrderStatus)
@Serializable data class ReturnItemRequest(val variantId: String, val quantity: Int)
@Serializable data class ReturnRequest(val reason: String, val items: List<ReturnItemRequest>)
@Serializable data class ReturnResponse(val id: String, val orderId: String, val status: String, val reason: String, val items: List<ReturnItemRequest>, val createdAt: String)
