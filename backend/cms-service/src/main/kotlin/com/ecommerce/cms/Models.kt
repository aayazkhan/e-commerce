package com.ecommerce.cms

import kotlinx.serialization.Serializable

@Serializable enum class CmsStatus { DRAFT, IN_REVIEW, APPROVED, PUBLISHED, SCHEDULED, UNPUBLISHED, ARCHIVED }
@Serializable data class SeoMetadata(val title:String?=null,val metaTitle:String?=null,val metaDescription:String?=null,val canonicalUrl:String?=null,val slug:String?=null,val robots:String?=null,val structuredData:String?=null,val ogTitle:String?=null,val ogDescription:String?=null,val ogImage:String?=null)
@Serializable data class CmsPageRequest(val slug:String,val title:String,val contentJson:String,val seo:SeoMetadata=SeoMetadata(),val expectedVersion:Long?=null)
@Serializable data class CmsPublishRequest(val version:Int?=null,val publishAt:String?=null,val unpublishAt:String?=null,val timezone:String="UTC")
@Serializable data class CmsRollbackRequest(val version:Int,val expectedVersion:Long)
@Serializable data class CmsPageResponse(val id:String,val slug:String,val title:String,val status:CmsStatus,val currentVersion:Int,val version:Long,val contentJson:String,val seo:SeoMetadata,val createdBy:String,val createdAt:String,val updatedAt:String)
