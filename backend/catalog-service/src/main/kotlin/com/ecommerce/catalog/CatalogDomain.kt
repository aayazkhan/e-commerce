package com.ecommerce.catalog

import kotlinx.serialization.Serializable

enum class ProductStatus { DRAFT, REVIEW, ACTIVE, INACTIVE, OUT_OF_STOCK, ARCHIVED }
enum class OwnerType { SELLER, ADMIN, SYSTEM }
enum class VariantStatus { ACTIVE, INACTIVE, ARCHIVED }

@Serializable
data class Product(
    val id: String,
    val sellerId: String,
    val ownerType: OwnerType,
    val brandId: String?,
    val categoryId: String,
    val name: String,
    val slug: String,
    val description: String,
    val shortDescription: String?,
    val skuReference: String?,
    val status: ProductStatus,
    val taxCategory: String?,
    val attributes: Map<String, String>,
    val seoTitle: String?,
    val seoDescription: String?,
    val canonicalUrl: String?,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
    val variants: List<ProductVariant> = emptyList(),
    val media: List<ProductMedia> = emptyList(),
)

@Serializable
data class ProductVariant(val id: String, val productId: String, val sku: String, val barcode: String?, val attributes: Map<String, String>, val priceReference: String?, val weightGrams: Int?, val dimensions: Map<String, String>, val status: VariantStatus)

@Serializable
data class ProductMedia(val mediaId: String, val mediaType: String, val url: String?, val sortOrder: Int, val altText: String?)

@Serializable
data class ProductEventPayload(val productId: String, val slug: String, val categoryId: String, val status: String, val version: Long, val name: String = "", val description: String = "", val shortDescription: String? = null, val brandId: String? = null, val attributes: Map<String, String> = emptyMap(), val media: List<ProductMedia> = emptyList())

fun validateProductSlug(slug: String) { require(slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Slug must be lowercase URL-safe text" } }
fun validateProductName(name: String) { require(name.trim().length in 1..255) { "Product name is invalid" } }
