package com.ecommerce.wishlist

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.InternalHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration

interface WishlistRouteEnricher { fun enrich(record: WishlistRecord, currency: String): WishlistItemView }

class WishlistEnricher(pricingBaseUrl: String, inventoryBaseUrl: String) : WishlistRouteEnricher {
    private val pricing = InternalHttpClient(pricingBaseUrl, Duration.ofSeconds(2))
    private val inventory = InternalHttpClient(inventoryBaseUrl, Duration.ofSeconds(2))
    private val json = Json { ignoreUnknownKeys = true }

    override fun enrich(record: WishlistRecord, currency: String): WishlistItemView {
        val priceResponse = pricing.get("/api/v1/pricing/products/${record.productId}?variantId=${record.variantId}&currency=$currency")
        val price = if (priceResponse.statusCode == 404) null else if (priceResponse.statusCode in 200..299) runCatching { json.decodeFromString<PriceDto>(priceResponse.body) }.getOrNull() else throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Pricing service is unavailable.", 503, retryable = true)
        val inventoryResponse = inventory.get("/api/v1/inventory/items/${record.variantId}")
        if (inventoryResponse.statusCode !in 200..299) throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Inventory service is unavailable.", 503, retryable = true)
        val available = runCatching { json.decodeFromString<List<InventoryDto>>(inventoryResponse.body).sumOf { it.available } }.getOrElse { throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Inventory response is invalid.", 503, retryable = true) }
        return WishlistItemView(record.id, record.productId, record.variantId, record.createdAt, when { price == null -> "PRICE_UNAVAILABLE"; available > 0 -> "IN_STOCK"; else -> "OUT_OF_STOCK" }, available, price?.let { it.saleMinor ?: it.baseMinor }, price?.currency)
    }
}

@Serializable internal data class PriceDto(val currency: String, val baseMinor: Long, val saleMinor: Long? = null)
@Serializable private data class InventoryDto(val available: Long)
