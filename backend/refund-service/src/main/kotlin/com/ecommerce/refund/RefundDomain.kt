package com.ecommerce.refund

import kotlinx.serialization.Serializable

@Serializable enum class RefundStatus { REQUESTED, APPROVED, PROCESSING, COMPLETED, FAILED, REJECTED, CANCELLED }
@Serializable enum class RefundType { FULL, PARTIAL, ITEM_LEVEL, SHIPPING, TAX }
@Serializable data class RefundItem(val variantId:String,val quantity:Int,val amountMinor:Long)
@Serializable data class RefundRequest(val orderId:String,val paymentId:String,val amountMinor:Long,val currency:String,val type:RefundType,val reason:String,val items:List<RefundItem> = emptyList())
@Serializable data class RefundResponse(val id:String,val userId:String,val orderId:String,val paymentId:String,val amountMinor:Long,val currency:String,val type:RefundType,val status:RefundStatus,val reason:String,val items:List<RefundItem>,val providerRefundId:String?=null,val createdAt:String,val updatedAt:String)
@Serializable data class RefundDecision(val approve:Boolean,val reason:String?=null)
@Serializable data class PaymentRefundResult(val paymentId:String,val amountMinor:Long,val status:String,val providerRefundId:String?,val createdAt:String)
