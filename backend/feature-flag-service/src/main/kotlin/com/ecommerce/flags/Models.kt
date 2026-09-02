package com.ecommerce.flags

import kotlinx.serialization.Serializable

@Serializable enum class FlagValueType { BOOLEAN, STRING, INTEGER, JSON }
@Serializable data class FeatureFlagRequest(val key:String,val valueType:FlagValueType,val defaultValue:String,val failSafeValue:String,val enabled:Boolean=true,val environment:String="production",val rolloutBps:Int=10000,val rulesJson:String="{}",val expectedVersion:Long?=null)
@Serializable data class FeatureFlagResponse(val key:String,val valueType:FlagValueType,val defaultValue:String,val failSafeValue:String,val enabled:Boolean,val environment:String,val rolloutBps:Int,val rulesJson:String,val version:Long,val updatedBy:String,val updatedAt:String)
@Serializable data class EvaluationRequest(val key:String,val userId:String?=null,val country:String?=null,val platform:String?=null,val appVersion:String?=null,val sellerId:String?=null,val environment:String="production",val fallbackValue:String="false")
@Serializable data class EvaluationResponse(val key:String,val value:String,val enabled:Boolean,val version:Long,val source:String)

fun deterministicBucket(identity:String,key:String):Int {
    val bytes=java.security.MessageDigest.getInstance("SHA-256").digest("$key:$identity".toByteArray())
    return ((bytes[0].toInt() and 0xff)*256+(bytes[1].toInt() and 0xff))%10000
}
