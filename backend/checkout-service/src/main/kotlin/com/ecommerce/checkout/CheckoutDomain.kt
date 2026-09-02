package com.ecommerce.checkout

import kotlinx.serialization.Serializable

@Serializable enum class CheckoutStatus { CREATED, VALIDATING, INVENTORY_RESERVING, INVENTORY_RESERVED, ORDER_CREATING, PAYMENT_PROCESSING, PAYMENT_ACTION_REQUIRED, COMPLETED, COMPENSATING, RECOVERABLE, FAILED }
@Serializable enum class CheckoutStep { VALIDATE_CART, QUOTE_PRICING, QUOTE_PROMOTION, QUOTE_SHIPPING, RESERVE_INVENTORY, CREATE_ORDER, CREATE_PAYMENT, COMMIT_INVENTORY, CREATE_SHIPMENT, COMPLETE }
@Serializable enum class CheckoutShippingMethod { STANDARD, EXPRESS, SAME_DAY }
@Serializable data class CheckoutRequest(val cartId:String?=null,val shippingAddressId:String,val billingAddressId:String?=null,val shippingMethod:CheckoutShippingMethod=CheckoutShippingMethod.STANDARD,val paymentMethodToken:String,val paymentProvider:String="HTTP",val currency:String="INR",val couponCode:String?=null)
@Serializable data class CheckoutPayment(val id:String,val status:String,val clientSecret:String?=null)
@Serializable data class CheckoutTotals(val subtotalMinor:Long,val itemDiscountMinor:Long,val promotionDiscountMinor:Long,val shippingMinor:Long,val taxMinor:Long,val totalMinor:Long,val currency:String)
@Serializable data class CheckoutResponse(val checkoutId:String,val status:CheckoutStatus,val currentStep:CheckoutStep,val orderId:String?=null,val reservationId:String?=null,val promotionRedemptionId:String?=null,val payment:CheckoutPayment?=null,val totals:CheckoutTotals?=null,val error:String?=null,val createdAt:String,val updatedAt:String)
@Serializable data class CheckoutValidationResponse(val valid:Boolean,val totals:CheckoutTotals?,val warnings:List<String> = emptyList())
