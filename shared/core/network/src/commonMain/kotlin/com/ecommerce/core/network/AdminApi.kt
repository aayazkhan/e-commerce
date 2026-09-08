package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

@Serializable
data class SellerStatusTransitionRequest(val status: String, val reason: String? = null)

/**
 * Admin-facing calls, routed directly through the gateway to seller-service/catalog-service --
 * there's no admin-service in the loop for these two flows (seller approval queue, catalog
 * moderation): seller-service exposes its own /api/v1/admin/sellers, and catalog-service's public
 * /api/v1/products already widens to all statuses/sellers for an ADMIN-role JWT (see
 * catalog-service's Application.kt "privileged caller" check).
 */
class AdminApi(private val client: ApiClient) {
    suspend fun listSellers(status: String? = null): ApiResult<List<SellerProfile>> =
        client.get("/api/v1/admin/sellers" + (status?.let { "?status=$it" }.orEmpty()))

    suspend fun transitionSeller(id: String, status: String, reason: String? = null): ApiResult<SellerProfile> =
        client.post("/api/v1/admin/sellers/$id/status", SellerStatusTransitionRequest(status, reason))

    suspend fun listProducts(status: String? = null): ApiResult<SellerProductPage> =
        client.get("/api/v1/products" + (status?.let { "?status=$it" }.orEmpty()))

    suspend fun publishProduct(id: String): ApiResult<SellerProductDetail> =
        client.post("/api/v1/products/$id/publish", EmptyBody)
}

@Serializable
private object EmptyBody
