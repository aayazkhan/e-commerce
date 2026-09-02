package com.ecommerce.seller

import kotlinx.serialization.Serializable

@Serializable enum class SellerStatus { PENDING, UNDER_REVIEW, VERIFIED, ACTIVE, SUSPENDED, REJECTED, CLOSED }
@Serializable enum class LedgerEntryType { SALE, COMMISSION, REFUND, ADJUSTMENT, PAYOUT }
@Serializable data class SellerApplication(val displayName:String, val legalName:String, val email:String, val phone:String?=null)
@Serializable data class SellerProfileUpdate(val displayName:String, val legalName:String, val phone:String?=null, val version:Long)
@Serializable data class SellerResponse(val id:String,val ownerUserId:String,val displayName:String,val legalName:String,val email:String,val phone:String?,val status:SellerStatus,val version:Long,val createdAt:String,val updatedAt:String)
@Serializable data class SellerStatusRequest(val status:SellerStatus,val reason:String?=null)
@Serializable data class LedgerEntryRequest(val entryType:LedgerEntryType,val referenceId:String,val amountMinor:Long,val currency:String="INR",val description:String?=null)
@Serializable data class LedgerEntry(val id:String,val entryType:LedgerEntryType,val referenceId:String,val amountMinor:Long,val currency:String,val description:String?,val createdAt:String)
@Serializable data class SellerOrderItem(val orderId:String,val productId:String,val variantId:String,val quantity:Int,val lineTotalMinor:Long,val currency:String,val status:String?,val occurredAt:String)

fun isValidSellerTransition(from:SellerStatus,to:SellerStatus)=when(from){SellerStatus.PENDING->to in setOf(SellerStatus.UNDER_REVIEW,SellerStatus.REJECTED);SellerStatus.UNDER_REVIEW->to in setOf(SellerStatus.VERIFIED,SellerStatus.REJECTED);SellerStatus.VERIFIED->to==SellerStatus.ACTIVE;SellerStatus.ACTIVE->to in setOf(SellerStatus.SUSPENDED,SellerStatus.CLOSED);SellerStatus.SUSPENDED->to in setOf(SellerStatus.ACTIVE,SellerStatus.CLOSED);SellerStatus.REJECTED->to==SellerStatus.UNDER_REVIEW;SellerStatus.CLOSED->false}
