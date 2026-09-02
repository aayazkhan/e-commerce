package com.ecommerce.shipping

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ShippingSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `shipping request quote and shipment response round trip`() {
        val address = ShippingAddress("Ayyaz Khan", "+919999999999", "1 Main Road", "Floor 2", "Mumbai", "MH", "400001", "IN")
        val items = listOf(ShipmentItem("variant-1", 2), ShipmentItem("variant-2", 1))
        val quoteRequest = ShippingQuoteRequest(address, items, ShippingMethod.EXPRESS, "INR")
        val quote = ShippingQuote("quote-1", ShippingMethod.EXPRESS, 149, "INR", "2026-08-20T11:00:00Z")
        val create = ShipmentCreateRequest("order-1", "user-1", address, items, ShippingMethod.SAME_DAY, 299, "INR")
        val response = ShipmentResponse("shipment-1", "order-1", "user-1", "HTTP", "provider-1", ShipmentStatus.IN_TRANSIT, ShippingMethod.SAME_DAY, "TRACK1", "Carrier", 299, "INR", "2026-08-20T10:00:00Z", "2026-08-20T10:05:00Z")
        val webhook = TrackingWebhook("event-1", "provider-1", ShipmentStatus.DELIVERED, "TRACK1", mapOf("location" to "Mumbai"))
        assertEquals(quoteRequest, json.decodeFromString<ShippingQuoteRequest>(json.encodeToString(quoteRequest)))
        assertEquals(quote, json.decodeFromString<ShippingQuote>(json.encodeToString(quote)))
        assertEquals(create, json.decodeFromString<ShipmentCreateRequest>(json.encodeToString(create)))
        assertEquals(response, json.decodeFromString<ShipmentResponse>(json.encodeToString(response)))
        assertEquals(webhook, json.decodeFromString<TrackingWebhook>(json.encodeToString(webhook)))
    }

    @Test
    fun `compact shipping serialization preserves nullable tracking and address values`() {
        val address = ShippingAddress("Customer", "phone", "Line 1", city = "Pune", state = "MH", postalCode = "411001", country = "IN")
        val items = listOf(ShipmentItem("variant-1", 1))
        val quoteRequest = ShippingQuoteRequest(address, items, ShippingMethod.STANDARD, "INR")
        val response = ShipmentResponse("shipment-1", "order-1", "user-1", "HTTP", null, ShipmentStatus.CREATED, ShippingMethod.STANDARD, null, null, 0, "INR", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val webhook = TrackingWebhook("event-1", "provider-1", ShipmentStatus.EXCEPTION, null)
        assertEquals(quoteRequest, compactJson.decodeFromString(ShippingQuoteRequest.serializer(), compactJson.encodeToString(ShippingQuoteRequest.serializer(), quoteRequest)))
        assertEquals(response, compactJson.decodeFromString(ShipmentResponse.serializer(), compactJson.encodeToString(ShipmentResponse.serializer(), response)))
        assertEquals(webhook, compactJson.decodeFromString(TrackingWebhook.serializer(), compactJson.encodeToString(TrackingWebhook.serializer(), webhook)))
    }
}
