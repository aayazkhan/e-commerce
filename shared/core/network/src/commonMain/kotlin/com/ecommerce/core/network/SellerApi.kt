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

/** Full product shape returned by GET/POST/PATCH -- mirrors catalog-service's Product response. */
@Serializable
data class SellerProductDetail(
    val id: String,
    val sellerId: String,
    val categoryId: String,
    val name: String,
    val slug: String,
    val description: String,
    val shortDescription: String? = null,
    val status: String,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Request body for create (POST) and update (PATCH) -- mirrors catalog-service's ProductRequest.
 * ownerType/sellerId are overwritten server-side by seller-service, so they're omitted here.
 * variants/media default to empty on the server and aren't collected by this form yet.
 */
@Serializable
data class SellerProductRequest(
    val categoryId: String,
    val name: String,
    val slug: String,
    val description: String,
    val shortDescription: String? = null,
    val status: String = "DRAFT",
)

@Serializable
data class SellerMessage(val message: String)

class SellerApi(private val client: ApiClient) {
    suspend fun getProfile(): ApiResult<SellerProfile> = client.get("/api/v1/seller/profile")

    suspend fun listProducts(): ApiResult<SellerProductPage> = client.get("/api/v1/seller/products")

    suspend fun getProduct(id: String): ApiResult<SellerProductDetail> = client.get("/api/v1/seller/products/$id")

    suspend fun createProduct(request: SellerProductRequest): ApiResult<SellerProductDetail> =
        client.post("/api/v1/seller/products", request)

    suspend fun updateProduct(id: String, request: SellerProductRequest): ApiResult<SellerProductDetail> =
        client.patch("/api/v1/seller/products/$id", request)

    suspend fun deleteProduct(id: String): ApiResult<SellerMessage> = client.delete("/api/v1/seller/products/$id")
}
