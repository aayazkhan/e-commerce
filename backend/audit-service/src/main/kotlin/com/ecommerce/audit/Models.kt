package com.ecommerce.audit

import kotlinx.serialization.Serializable

@Serializable data class AuditRequest(val actorId:String?=null,val actorType:String="SYSTEM",val action:String,val resourceType:String,val resourceId:String,val sellerId:String?=null,val beforeJson:String?=null,val afterJson:String?=null,val reason:String?=null,val requestId:String?=null,val traceId:String?=null,val ip:String?=null,val userAgent:String?=null)
@Serializable data class AuditResponse(val id:String,val actorId:String?,val actorType:String,val action:String,val resourceType:String,val resourceId:String,val sellerId:String?,val beforeJson:String?,val afterJson:String?,val reason:String?,val requestId:String?,val traceId:String?,val createdAt:String)
@Serializable data class AuditPage(val items:List<AuditResponse>,val nextCursor:String?)
