package com.ecommerce.cart

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.InternalHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration

class PricingClient(baseUrl: String) : CartPricing {
    private val client = InternalHttpClient(baseUrl, Duration.ofSeconds(2))
    private val json = Json { ignoreUnknownKeys = true }

    override fun current(productId: String, variantId: String, currency: String): PriceSnapshot {
        val response = client.get("/api/v1/pricing/products/$productId?variantId=$variantId&currency=$currency")
        if (response.statusCode == 404) throw ApiException(ErrorCode.NOT_FOUND, "Price not found for this variant.", 404)
        if (response.statusCode !in 200..299) throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Pricing service is unavailable.", 503, retryable = true)
        val price = runCatching { json.decodeFromString<PriceDto>(response.body) }.getOrElse { throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Pricing response is invalid.", 503, retryable = true) }
        return PriceSnapshot(price.saleMinor ?: price.baseMinor, price.currency, price.version.toString())
    }
}

class InventoryClient(baseUrl: String) : CartInventory {
    private val client = InternalHttpClient(baseUrl, Duration.ofSeconds(2))
    private val json = Json { ignoreUnknownKeys = true }

    override fun available(variantId: String): Long {
        val response = client.get("/api/v1/inventory/items/$variantId")
        if (response.statusCode !in 200..299) throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Inventory service is unavailable.", 503, retryable = true)
        return runCatching { json.decodeFromString<List<InventoryDto>>(response.body).sumOf { it.available } }.getOrElse { throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Inventory response is invalid.", 503, retryable = true) }
    }
}

@Serializable
internal data class PriceDto(
    val currency: String,
    val baseMinor: Long,
    val saleMinor: Long? = null,
    val version: Long,
)

@Serializable
internal data class InventoryDto(val available: Long)
