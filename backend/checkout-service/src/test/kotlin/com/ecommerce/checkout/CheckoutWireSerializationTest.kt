package com.ecommerce.checkout

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckoutWireSerializationTest {
    private val compact = Json { encodeDefaults = false; explicitNulls = false }
    private val complete = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `price and promotion request wires preserve defaults and supplied filters`() {
        val item = PriceItemWire("product-1", "variant-1", 2)
        val defaultPrice = PriceRequest(listOf(item))
        assertEquals(defaultPrice, compact.decodeFromString<PriceRequest>(compact.encodeToString(defaultPrice)))
        assertFalse(compact.encodeToString(defaultPrice).contains("country"))
        assertEquals(defaultPrice, complete.decodeFromString<PriceRequest>(complete.encodeToString(defaultPrice)))
        listOf(
            PriceRequest(listOf(item), currency = "USD"),
            PriceRequest(listOf(item), country = "US"),
            PriceRequest(listOf(item), customerSegment = "VIP"),
            PriceRequest(listOf(item), currency = "USD", country = "US"),
            PriceRequest(listOf(item), currency = "USD", customerSegment = "VIP"),
            PriceRequest(listOf(item), country = "US", customerSegment = "VIP"),
        ).forEach { value ->
            assertEquals(value, compact.decodeFromString<PriceRequest>(compact.encodeToString(value)))
        }

        val filteredPrice = PriceRequest(listOf(item), "USD", "US", "VIP")
        assertEquals(filteredPrice, complete.decodeFromString<PriceRequest>(complete.encodeToString(filteredPrice)))
        assertEquals(listOf(item), filteredPrice.items)
        assertEquals("USD", filteredPrice.currency)
        assertEquals("US", filteredPrice.country)
        assertEquals("VIP", filteredPrice.customerSegment)

        val line = PromotionLineWire("product-1", "variant-1", 2, 500)
        val noCoupon = PromotionRequestWire("INR", listOf(line))
        assertEquals(noCoupon, compact.decodeFromString<PromotionRequestWire>(compact.encodeToString(noCoupon)))
        assertFalse(compact.encodeToString(noCoupon).contains("couponCode"))
        assertEquals(noCoupon, complete.decodeFromString<PromotionRequestWire>(complete.encodeToString(noCoupon)))
        listOf(
            PromotionRequestWire("INR", listOf(line), couponCode = "SAVE10"),
            PromotionRequestWire("INR", listOf(line), shippingMinor = 50),
        ).forEach { value ->
            assertEquals(value, compact.decodeFromString<PromotionRequestWire>(compact.encodeToString(value)))
        }

        val coupon = PromotionRequestWire("INR", listOf(line), "SAVE10", shippingMinor = 50)
        assertEquals(coupon, complete.decodeFromString<PromotionRequestWire>(complete.encodeToString(coupon)))
        assertEquals("INR", coupon.currency)
        assertEquals(listOf(line), coupon.lines)
        assertEquals("SAVE10", coupon.couponCode)
        assertEquals(50, coupon.shippingMinor)
    }

    @Test
    fun `promotion quote wire preserves nullable fields, discounts and default collections`() {
        val empty = PromotionQuoteWire(null, null, "INR", 0, freeShipping = false)
        assertEquals(empty, compact.decodeFromString<PromotionQuoteWire>(compact.encodeToString(empty)))
        assertFalse(compact.encodeToString(empty).contains("promotionId"))
        assertEquals(empty, complete.decodeFromString<PromotionQuoteWire>(complete.encodeToString(empty)))
        listOf(
            empty.copy(eligibleLineDiscounts = mapOf("variant-1" to 100)),
            empty.copy(reason = "Not eligible"),
        ).forEach { value ->
            assertEquals(value, compact.decodeFromString<PromotionQuoteWire>(compact.encodeToString(value)))
        }

        val applied = PromotionQuoteWire(
            promotionId = "promotion-1",
            couponCode = "SAVE10",
            currency = "INR",
            discountMinor = 100,
            freeShipping = true,
            eligibleLineDiscounts = mapOf("variant-1" to 100),
            reason = "Eligible",
        )
        assertEquals(applied, complete.decodeFromString<PromotionQuoteWire>(complete.encodeToString(applied)))
        assertTrue(complete.encodeToString(applied).contains("eligibleLineDiscounts"))
        assertEquals("SAVE10", applied.couponCode)
        assertEquals("INR", applied.currency)
        assertTrue(applied.freeShipping)
        assertEquals(mapOf("variant-1" to 100L), applied.eligibleLineDiscounts)
    }

    @Test
    fun `address wire preserves nullable location fields and default flag`() {
        val sparse = AddressWire(
            id = "address-1", label = "HOME", recipientName = "Customer", phone = "+911234567890",
            line1 = "Line 1", line2 = null, city = "Pune", state = "MH", postalCode = "411001", country = "IN",
        )
        assertEquals(sparse, compact.decodeFromString<AddressWire>(compact.encodeToString(sparse)))
        val sparseJson = compact.encodeToString(sparse)
        assertFalse(sparseJson.contains("line2"))
        assertFalse(sparseJson.contains("isDefault"))
        assertEquals(sparse, complete.decodeFromString<AddressWire>(complete.encodeToString(sparse)))
        listOf(
            sparse.copy(line2 = "Floor 2"),
            sparse.copy(latitude = 18.5204),
            sparse.copy(longitude = 73.8567),
            sparse.copy(isDefault = true),
            sparse.copy(latitude = 18.5204, longitude = 73.8567),
            sparse.copy(latitude = 18.5204, isDefault = true),
            sparse.copy(longitude = 73.8567, isDefault = true),
        ).forEach { value ->
            assertEquals(value, compact.decodeFromString<AddressWire>(compact.encodeToString(value)))
        }

        val completeAddress = sparse.copy(line2 = "Floor 2", latitude = 18.5204, longitude = 73.8567, isDefault = true)
        assertEquals(completeAddress, complete.decodeFromString<AddressWire>(complete.encodeToString(completeAddress)))
        assertEquals("Floor 2", completeAddress.line2)
        assertEquals("HOME", completeAddress.label)
        assertEquals(18.5204, completeAddress.latitude)
        assertEquals(73.8567, completeAddress.longitude)
        assertTrue(completeAddress.isDefault)
    }

    @Test
    fun `product wire preserves omitted variants and complete variant metadata`() {
        val empty = ProductWire("product-1", "Shoe")
        assertEquals(empty, compact.decodeFromString<ProductWire>(compact.encodeToString(empty)))
        assertFalse(compact.encodeToString(empty).contains("variants"))

        val variant = ProductVariantWire(
            id = "variant-1", productId = "product-1", sku = "SKU-1", barcode = "8901234567890",
            attributes = mapOf("size" to "9", "color" to "black"),
        )
        val defaultVariant = ProductVariantWire("variant-1", "product-1", "SKU-1")
        assertEquals(defaultVariant, complete.decodeFromString<ProductVariantWire>(complete.encodeToString(defaultVariant)))
        val barcodeOnly = defaultVariant.copy(barcode = "8901234567890")
        val attributesOnly = defaultVariant.copy(attributes = mapOf("size" to "9"))
        assertEquals(barcodeOnly, compact.decodeFromString<ProductVariantWire>(compact.encodeToString(barcodeOnly)))
        assertEquals(attributesOnly, compact.decodeFromString<ProductVariantWire>(compact.encodeToString(attributesOnly)))
        val product = ProductWire("product-1", "Shoe", listOf(variant))
        assertEquals(product, complete.decodeFromString<ProductWire>(complete.encodeToString(product)))
        val emptyProduct = ProductWire("product-1", "Shoe")
        assertEquals(emptyProduct, complete.decodeFromString<ProductWire>(complete.encodeToString(emptyProduct)))
        assertEquals("product-1", product.id)
        assertEquals("Shoe", product.name)
        assertEquals(listOf(variant), product.variants)
        assertEquals("8901234567890", product.variants.single().barcode)
        assertEquals("black", product.variants.single().attributes["color"])
        assertEquals("variant-1", variant.id)
        assertEquals("product-1", variant.productId)
        assertEquals("SKU-1", variant.sku)
        assertEquals("8901234567890", variant.barcode)
        assertEquals(mapOf("size" to "9", "color" to "black"), variant.attributes)
    }

    @Test
    fun `wire decoders reject missing required values`() {
        assertFailsWith<SerializationException> {
            compact.decodeFromString<PriceRequest>("{}")
        }
        val omittedNullableQuote = compact.decodeFromString<PromotionQuoteWire>(
            "{\"currency\":\"INR\",\"discountMinor\":0,\"freeShipping\":false}",
        )
        assertEquals(null, omittedNullableQuote.promotionId)
        assertEquals(null, omittedNullableQuote.reason)
        assertFailsWith<SerializationException> {
            compact.decodeFromString<AddressWire>("{}")
        }
        assertFailsWith<SerializationException> {
            compact.decodeFromString<ProductVariantWire>("{\"id\":\"variant-1\"}")
        }
    }

    @Test
    fun `promotion and order envelopes preserve nullable and default fields`() {
        val request = PromotionRequestWire("INR", listOf(PromotionLineWire("p1", "v1", 1, 500)))
        val withoutOrder = PromotionApplyEnvelopeWire("user-1", request)
        val withOrder = PromotionApplyEnvelopeWire("user-1", request, "order-1")
        assertEquals(withoutOrder, compact.decodeFromString<PromotionApplyEnvelopeWire>(compact.encodeToString(withoutOrder)))
        assertEquals(withOrder, compact.decodeFromString<PromotionApplyEnvelopeWire>(compact.encodeToString(withOrder)))
        assertEquals(withoutOrder, complete.decodeFromString<PromotionApplyEnvelopeWire>(complete.encodeToString(withoutOrder)))

        val address = AddressSnapshotWire("address-1", "Customer", "+911234567890", "Line 1", null, "Pune", "MH", "411001", "IN")
        val item = OrderItemWire("p1", "v1", "Shoe", quantity = 1, unitPriceMinor = 500, taxMinor = 90, discountMinor = 0, lineTotalMinor = 590, currency = "INR", priceVersion = "v1")
        val defaultStatus = OrderRequestWire("checkout-1", "reservation-1", listOf(item), address, address, 500, 0, 0, 0, 90, 590, "INR")
        val customStatus = defaultStatus.copy(initialStatus = "PENDING")
        assertEquals(defaultStatus, compact.decodeFromString<OrderRequestWire>(compact.encodeToString(defaultStatus)))
        assertEquals(customStatus, compact.decodeFromString<OrderRequestWire>(compact.encodeToString(customStatus)))
        assertEquals(defaultStatus, complete.decodeFromString<OrderRequestWire>(complete.encodeToString(defaultStatus)))
        assertFailsWith<SerializationException> {
            compact.decodeFromString<OrderRequestWire>("{}")
        }
    }
}
