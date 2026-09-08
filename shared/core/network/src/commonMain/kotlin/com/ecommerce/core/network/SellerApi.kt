package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

/** Mirrors backend/seller-service/.../Models.kt's SellerResponse exactly. */
@Serializable
data class SellerProfile(
    val id: String,
    val ownerUserId: String,
    val displayName: String,
    val legalName: String,
    val email: String,
    val phone: String? = null,
    val status: String,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * A read-model subset of catalog-service's Product (backend/catalog-service/.../CatalogDomain.kt) --
 * GET /api/v1/seller/products proxies straight through to catalog-service's product page for the
 * caller's own sellerId (see seller-service's Application.kt), so this is that same response shape.
 */
@Serializable
data class SellerProductPage(
    val items: List<SellerProduct> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class SellerProduct(
    val id: String,
    val name: String,
    val slug: String,
    val status: String,
    val categoryId: String,
    val updatedAt: String,
)

class SellerApi(private val client: ApiClient) {
    suspend fun getProfile(): ApiResult<SellerProfile> = client.get("/api/v1/seller/profile")

    suspend fun listProducts(): ApiResult<SellerProductPage> = client.get("/api/v1/seller/products")
}
