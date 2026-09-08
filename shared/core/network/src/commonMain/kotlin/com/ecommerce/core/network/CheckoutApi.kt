package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

/** Mirrors backend/checkout-service's CheckoutDomain.kt exactly. */
@Serializable
enum class CheckoutShippingMethod { STANDARD, EXPRESS }

@Serializable
data class CheckoutRequest(
    val shippingAddressId: String,
    val billingAddressId: String? = null,
    val shippingMethod: CheckoutShippingMethod = CheckoutShippingMethod.STANDARD,
    val paymentMethodToken: String,
    val paymentProvider: String = "COD",
    val currency: String = "INR",
    val couponCode: String? = null,
)

@Serializable
data class CheckoutTotals(
    val subtotalMinor: Long,
    val itemDiscountMinor: Long,
    val promotionDiscountMinor: Long,
    val shippingMinor: Long,
    val taxMinor: Long,
    val totalMinor: Long,
    val currency: String,
)

@Serializable
data class CheckoutPayment(val id: String, val status: String, val clientSecret: String? = null)

@Serializable
data class CheckoutResponse(
    val checkoutId: String,
    val status: String,
    val currentStep: String,
    val orderId: String? = null,
    val reservationId: String? = null,
    val promotionRedemptionId: String? = null,
    val payment: CheckoutPayment? = null,
    val totals: CheckoutTotals? = null,
    val error: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/** Requires a logged-in user (guest checkout is not supported -- the cart must be merged into a user cart first). */
class CheckoutApi(private val client: ApiClient) {
    suspend fun start(request: CheckoutRequest, idempotencyKey: String): ApiResult<CheckoutResponse> =
        client.post("/api/v1/checkout", request, mapOf("Idempotency-Key" to idempotencyKey))

    suspend fun get(checkoutId: String): ApiResult<CheckoutResponse> = client.get("/api/v1/checkout/$checkoutId")
}
