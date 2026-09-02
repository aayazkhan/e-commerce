package com.ecommerce.admin

import kotlinx.serialization.Serializable

@Serializable data class BulkJobRequest(val itemIds:List<String>,val payloadJson:String="{}")
@Serializable data class AdminJobResponse(val id:String,val jobType:String,val status:String,val totalCount:Int,val successCount:Int,val failureCount:Int,val attempts:Int,val lastError:String?,val createdAt:String,val completedAt:String?)
