package com.ecommerce.payment

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentStatus { CREATED, REQUIRES_ACTION, PROCESSING, AUTHORIZED, CAPTURED, FAILED, CANCELLED, EXPIRED, REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED }
@Serializable
enum class PaymentProviderName { HTTP, COD, PAYU }

private val paymentTransitions = mapOf(
    PaymentStatus.CREATED to setOf(PaymentStatus.PROCESSING, PaymentStatus.REQUIRES_ACTION, PaymentStatus.AUTHORIZED, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
    PaymentStatus.REQUIRES_ACTION to setOf(PaymentStatus.PROCESSING, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED, PaymentStatus.FAILED, PaymentStatus.EXPIRED, PaymentStatus.CANCELLED),
    PaymentStatus.PROCESSING to setOf(PaymentStatus.REQUIRES_ACTION, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
    PaymentStatus.AUTHORIZED to setOf(PaymentStatus.CAPTURED, PaymentStatus.CANCELLED, PaymentStatus.REFUND_PENDING),
    PaymentStatus.CAPTURED to setOf(PaymentStatus.REFUND_PENDING, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED),
    PaymentStatus.REFUND_PENDING to setOf(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED, PaymentStatus.FAILED),
    PaymentStatus.PARTIALLY_REFUNDED to setOf(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED),
)
fun assertPaymentTransition(from: PaymentStatus, to: PaymentStatus) { if (from != to && to !in paymentTransitions.getOrDefault(from, emptySet())) error("Payment cannot transition from $from to $to") }

@Serializable
data class PaymentCreateRequest(val orderId: String, val amountMinor: Long, val currency: String, val provider: PaymentProviderName, val paymentMethodToken: String, val returnUrl: String? = null, val customerName: String? = null, val customerEmail: String? = null, val customerPhone: String? = null, val checkoutId: String? = null)
@Serializable
data class PaymentResponse(val id: String, val orderId: String, val userId: String, val provider: PaymentProviderName, val providerPaymentId: String? = null, val status: PaymentStatus, val amountMinor: Long, val currency: String, val attempt: Int, val clientSecret: String? = null, val createdAt: String, val updatedAt: String)
@Serializable
data class PaymentWebhookRequest(val providerEventId: String, val providerPaymentId: String, val status: PaymentStatus, val amountMinor: Long, val currency: String, val payload: Map<String, String> = emptyMap())
@Serializable
data class RefundPaymentRequest(val amountMinor: Long, val currency: String, val idempotencyKey: String)
@Serializable
data class RefundPaymentResponse(val paymentId: String, val amountMinor: Long, val status: PaymentStatus, val providerRefundId: String?, val createdAt: String)

data class ProviderPayment(val providerPaymentId: String, val status: PaymentStatus, val clientSecret: String? = null)
data class ProviderRefund(val providerRefundId: String?, val status: PaymentStatus)
data class ProviderCreateRequest(val paymentId: String, val orderId: String, val amountMinor: Long, val currency: String, val paymentMethodToken: String, val returnUrl: String?, val customerName: String? = null, val customerEmail: String? = null, val customerPhone: String? = null, val checkoutId: String? = null)
interface PaymentProvider { val name: PaymentProviderName; fun create(request: ProviderCreateRequest): ProviderPayment; fun query(providerPaymentId: String): ProviderPayment; fun refund(providerPaymentId: String, amountMinor: Long, currency: String, idempotencyKey: String): ProviderRefund; fun verifyWebhook(body: String, signature: String?): Boolean }
