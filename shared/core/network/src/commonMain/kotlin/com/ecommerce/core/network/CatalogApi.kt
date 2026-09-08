package com.ecommerce.core.network

import com.ecommerce.core.common.ApiResult
import kotlinx.serialization.Serializable

/** Mirrors backend/catalog-service's public-facing Product/ProductVariant/ProductMedia shape. */
@Serializable
data class StorefrontProduct(
    val id: String,
    val sellerId: String,
    val categoryId: String,
    val name: String,
    val slug: String,
    val description: String,
    val shortDescription: String? = null,
    val status: String,
    val attributes: Map<String, String> = emptyMap(),
    val variants: List<StorefrontVariant> = emptyList(),
    val media: List<StorefrontMedia> = emptyList(),
)

@Serializable
data class StorefrontVariant(
    val id: String,
    val productId: String,
    val sku: String,
    val attributes: Map<String, String> = emptyMap(),
    val status: String,
)

@Serializable
data class StorefrontMedia(val mediaId: String, val mediaType: String, val url: String? = null, val sortOrder: Int = 0, val altText: String? = null)

@Serializable
data class ProductPage(val items: List<StorefrontProduct>, val nextCursor: String? = null, val hasMore: Boolean = false)

/** Mirrors pricing-service's PriceRecord response -- note the wire field is baseMinor, not unitMinor. */
@Serializable
data class PriceQuote(
    val productId: String,
    val variantId: String? = null,
    val currency: String,
    val baseMinor: Long,
)

/** Public product browsing -- routed to catalog-service via the gateway, no auth required. */
class CatalogApi(private val client: ApiClient) {
    suspend fun list(categoryId: String? = null, cursor: String? = null, limit: Int = 24): ApiResult<ProductPage> {
        val params = buildList {
            categoryId?.let { add("categoryId=$it") }
            cursor?.let { add("cursor=$it") }
            add("limit=$limit")
        }.joinToString("&")
        return client.get("/api/v1/products?$params")
    }

    suspend fun get(productId: String): ApiResult<StorefrontProduct> = client.get("/api/v1/products/$productId")

    suspend fun price(productId: String, variantId: String? = null, currency: String = "INR"): ApiResult<PriceQuote> {
        val query = buildList {
            add("currency=$currency")
            variantId?.let { add("variantId=$it") }
        }.joinToString("&")
        return client.get("/api/v1/pricing/products/$productId?$query")
    }
}
