package com.ecommerce.shipping

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShippingRepositoryValidationTest {
    private val address = ShippingAddress("Ayyaz", "+919999999999", "1 Main Road", city = "Mumbai", state = "MH", postalCode = "400001", country = "IN")
    private val provider = object : ShippingProvider {
        override val name = "fake"
        override fun quote(request: ShippingQuoteRequest) = ShippingQuote("q-1", request.method, 100, request.currency, "2026-08-21T00:00:00Z")
        override fun create(request: ShipmentCreateRequest) = ProviderShipment("p-1", ShipmentStatus.LABEL_CREATED, null, "fake")
        override fun track(providerShipmentId: String) = ProviderShipment(providerShipmentId, ShipmentStatus.IN_TRANSIT, null, "fake")
        override fun cancel(providerShipmentId: String) = ProviderShipment(providerShipmentId, ShipmentStatus.CANCELLED, null, "fake")
        override fun verifyWebhook(body: String, signature: String?) = true
    }
    private val repository = ShippingRepository(dataSource(), provider)

    @Test
    fun `quote rejects empty items and unsupported currencies`() {
        assertInvalidQuote(ShippingQuoteRequest(address, emptyList(), ShippingMethod.STANDARD, "INR"))
        assertInvalidQuote(ShippingQuoteRequest(address, listOf(ShipmentItem("v-1", 1)), ShippingMethod.STANDARD, "JPY"))
    }

    @Test
    fun `shipment creation rejects every required invalid field`() {
        val item = ShipmentItem("v-1", 1)
        assertInvalidCreate(ShipmentCreateRequest("", "user-1", address, listOf(item), ShippingMethod.STANDARD, 100, "INR"))
        assertInvalidCreate(ShipmentCreateRequest("order-1", "", address, listOf(item), ShippingMethod.STANDARD, 100, "INR"))
        assertInvalidCreate(ShipmentCreateRequest("order-1", "user-1", address, emptyList(), ShippingMethod.STANDARD, 100, "INR"))
        assertInvalidCreate(ShipmentCreateRequest("order-1", "user-1", address, listOf(item), ShippingMethod.STANDARD, -1, "INR"))
    }

    private fun assertInvalidQuote(request: ShippingQuoteRequest) {
        val error = assertFailsWith<ApiException> { repository.quote("user-1", request) }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun assertInvalidCreate(request: ShipmentCreateRequest) {
        val error = assertFailsWith<ApiException> { repository.create(request, "key-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun dataSource(): DataSource {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, _, _ -> null }) as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ -> if (method.name == "getConnection") connection else null }) as DataSource
    }
}
