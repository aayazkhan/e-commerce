package com.ecommerce.category

import kotlinx.serialization.Serializable

enum class CategoryStatus { DRAFT, ACTIVE, INACTIVE, ARCHIVED }

@Serializable
data class Category(
    val id: String,
    val parentId: String?,
    val name: String,
    val slug: String,
    val description: String?,
    val imageUrl: String?,
    val icon: String?,
    val sortOrder: Int,
    val status: CategoryStatus,
    val seoTitle: String?,
    val seoDescription: String?,
    val seoKeywords: List<String>,
    val canonicalUrl: String?,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
    @kotlinx.serialization.Transient val path: String = "",
)

@Serializable
data class CategoryEventPayload(val categoryId: String, val slug: String, val status: String, val version: Long)

fun validateSlug(slug: String) {
    require(slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Slug must be lowercase URL-safe text" }
    require(slug.length <= 160) { "Slug is too long" }
}
