package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

/** Read-model subset of category-service's Category, enough to populate a picker. */
@Serializable
data class Category(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val slug: String,
)

/** Routed directly to category-service via the gateway (/api/v1/categories), no auth required. */
class CategoryApi(private val client: ApiClient) {
    suspend fun tree(): ApiResult<List<Category>> = client.get("/api/v1/categories/tree")
}
