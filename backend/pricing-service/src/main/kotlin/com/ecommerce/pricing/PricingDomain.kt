package com.ecommerce.pricing

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class Price(val id: String, val productId: String, val variantId: String?, val sellerId: String?, val currency: String, val region: String, val customerSegment: String, val baseMinor: Long, val saleMinor: Long?, val taxRateBps: Int, val effectiveFrom: String, val effectiveTo: String?, val version: Long, val createdBy: String, val createdAt: String, val updatedAt: String) {
    val unitMinor: Long get() = saleMinor ?: baseMinor
}

@Serializable
data class QuoteLine(val productId: String, val variantId: String?, val quantity: Int, val unitMinor: Long, val lineSubtotalMinor: Long, val lineTaxMinor: Long)

@Serializable
data class PriceQuote(val items: List<QuoteLine>, val subtotalMinor: Long, val discountMinor: Long, val taxMinor: Long, val shippingEstimateMinor: Long, val totalMinor: Long, val currency: String, val priceVersion: String)

@Serializable
data class PriceEventPayload(val priceId: String, val productId: String, val variantId: String?, val currency: String, val version: Long, val unitMinor: Long = 0, val taxRateBps: Int = 0)

fun calculateTaxMinor(subtotalMinor: Long, taxRateBps: Int): Long = BigDecimal.valueOf(subtotalMinor).multiply(BigDecimal.valueOf(taxRateBps.toLong())).divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact()
