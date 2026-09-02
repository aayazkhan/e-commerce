package com.ecommerce.seller

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SellerDomainSerializationTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `seller enums and compact wire models preserve optional defaults`() {
        SellerStatus.entries.forEach { status ->
            assertEquals(status, json.decodeFromString(SellerStatus.serializer(), json.encodeToString(SellerStatus.serializer(), status)))
        }
        LedgerEntryType.entries.forEach { type ->
            assertEquals(type, json.decodeFromString(LedgerEntryType.serializer(), json.encodeToString(LedgerEntryType.serializer(), type)))
        }

        val application = SellerApplication("Shop", "Shop Legal", "shop@example.com")
        val update = SellerProfileUpdate("Shop", "Shop Legal", version = 1)
        val response = SellerResponse("seller-1", "user-1", "Shop", "Shop Legal", "shop@example.com", null, SellerStatus.PENDING, 1, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val status = SellerStatusRequest(SellerStatus.ACTIVE)
        val ledgerRequest = LedgerEntryRequest(LedgerEntryType.SALE, "order-1", 1_000)
        val ledger = LedgerEntry("entry-1", LedgerEntryType.SALE, "order-1", 1_000, "INR", null, "2026-08-20T00:00:00Z")
        val orderItem = SellerOrderItem("order-1", "product-1", "variant-1", 1, 1_000, "INR", null, "2026-08-20T00:00:00Z")

        assertEquals(application, json.decodeFromString(SellerApplication.serializer(), json.encodeToString(SellerApplication.serializer(), application)))
        assertEquals(update, json.decodeFromString(SellerProfileUpdate.serializer(), json.encodeToString(SellerProfileUpdate.serializer(), update)))
        assertEquals(response, json.decodeFromString(SellerResponse.serializer(), json.encodeToString(SellerResponse.serializer(), response)))
        assertEquals(status, json.decodeFromString(SellerStatusRequest.serializer(), json.encodeToString(SellerStatusRequest.serializer(), status)))
        assertEquals(ledgerRequest, json.decodeFromString(LedgerEntryRequest.serializer(), json.encodeToString(LedgerEntryRequest.serializer(), ledgerRequest)))
        val compactJson = Json { encodeDefaults = false; explicitNulls = false }
        listOf(
            ledgerRequest,
            ledgerRequest.copy(currency = "USD"),
            ledgerRequest.copy(description = "sale"),
            ledgerRequest.copy(currency = "USD", description = "sale"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(LedgerEntryRequest.serializer(), compactJson.encodeToString(LedgerEntryRequest.serializer(), value)))
        }
        assertEquals(ledger, json.decodeFromString(LedgerEntry.serializer(), json.encodeToString(LedgerEntry.serializer(), ledger)))
        assertEquals(orderItem, json.decodeFromString(SellerOrderItem.serializer(), json.encodeToString(SellerOrderItem.serializer(), orderItem)))
    }

    @Test
    fun `seller serialization preserves nullable fields and every ledger state`() {
        val compactJson = Json { encodeDefaults = false; explicitNulls = false }
        val application = SellerApplication("Shop", "Legal", "shop@example.com", "+911234")
        val update = SellerProfileUpdate("Shop", "Legal", "+911234", 2)
        val response = SellerResponse("seller-1", "user-1", "Shop", "Legal", "shop@example.com", "+911234", SellerStatus.ACTIVE, 2, "created", "updated")
        val status = SellerStatusRequest(SellerStatus.SUSPENDED, "policy")
        val ledger = LedgerEntry("entry-1", LedgerEntryType.PAYOUT, "payout-1", 1_000, "USD", "settlement", "created")
        val order = SellerOrderItem("order-1", "product-1", "variant-1", 2, 2_000, "USD", "PAID", "occurred")

        assertEquals(application, compactJson.decodeFromString(SellerApplication.serializer(), compactJson.encodeToString(SellerApplication.serializer(), application)))
        assertEquals(update, compactJson.decodeFromString(SellerProfileUpdate.serializer(), compactJson.encodeToString(SellerProfileUpdate.serializer(), update)))
        assertEquals(response, compactJson.decodeFromString(SellerResponse.serializer(), compactJson.encodeToString(SellerResponse.serializer(), response)))
        assertEquals(status, compactJson.decodeFromString(SellerStatusRequest.serializer(), compactJson.encodeToString(SellerStatusRequest.serializer(), status)))
        assertEquals(ledger, compactJson.decodeFromString(LedgerEntry.serializer(), compactJson.encodeToString(LedgerEntry.serializer(), ledger)))
        assertEquals(order, compactJson.decodeFromString(SellerOrderItem.serializer(), compactJson.encodeToString(SellerOrderItem.serializer(), order)))
        SellerStatus.entries.forEach { value -> assertEquals(value, compactJson.decodeFromString(SellerStatus.serializer(), compactJson.encodeToString(SellerStatus.serializer(), value))) }
        LedgerEntryType.entries.forEach { value -> assertEquals(value, compactJson.decodeFromString(LedgerEntryType.serializer(), compactJson.encodeToString(LedgerEntryType.serializer(), value))) }
    }
}
