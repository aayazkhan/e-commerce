package com.ecommerce.order

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderRepositoryValidationTest {
    @Test
    fun `create rejects missing checkout empty items and malformed currency`() {
        val repository = OrderRepository(dataSource())
        assertInvalid(repository, validRequest().copy(checkoutId = ""), ErrorCode.VALIDATION_ERROR)
        assertInvalid(repository, validRequest().copy(items = emptyList()), ErrorCode.VALIDATION_ERROR)
        assertInvalid(repository, validRequest().copy(currency = "inr"), ErrorCode.VALIDATION_ERROR)
    }

    @Test
    fun `create rejects negative item values and unreconciled totals`() {
        val repository = OrderRepository(dataSource())
        assertInvalid(repository, validRequest().copy(items = listOf(validItem().copy(quantity = 0))), ErrorCode.VALIDATION_ERROR)
        assertInvalid(repository, validRequest().copy(items = listOf(validItem().copy(unitPriceMinor = -1))), ErrorCode.VALIDATION_ERROR)
        assertInvalid(repository, validRequest().copy(items = listOf(validItem().copy(taxMinor = -1))), ErrorCode.VALIDATION_ERROR)
        assertInvalid(repository, validRequest().copy(items = listOf(validItem().copy(discountMinor = -1))), ErrorCode.VALIDATION_ERROR)
        assertInvalid(repository, validRequest().copy(items = listOf(validItem().copy(lineTotalMinor = -1))), ErrorCode.VALIDATION_ERROR)
        assertInvalid(repository, validRequest().copy(totalMinor = 999), ErrorCode.CONFLICT)
        assertInvalid(repository, validRequest().copy(subtotalMinor = 999), ErrorCode.CONFLICT)
        assertInvalid(repository, validRequest().copy(totalMinor = 1_279), ErrorCode.CONFLICT)
        assertInvalid(repository, validRequest().copy(itemDiscountMinor = 2_000, totalMinor = -720), ErrorCode.CONFLICT)
    }

    private fun assertInvalid(repository: OrderRepository, request: OrderCreateRequest, expected: ErrorCode) {
        val error = assertFailsWith<ApiException> { repository.create("user-1", request, "key-1", "actor-1", "corr-1") }
        assertEquals(expected, error.errorCode)
    }

    private fun validRequest() = OrderCreateRequest(
        checkoutId = "checkout-1",
        reservationId = "reservation-1",
        items = listOf(validItem()),
        shippingAddress = address(),
        billingAddress = address(),
        subtotalMinor = 1000,
        itemDiscountMinor = 0,
        promotionDiscountMinor = 0,
        shippingMinor = 100,
        taxMinor = 180,
        totalMinor = 1280,
        currency = "INR",
    )

    private fun validItem() = OrderItemSnapshot("product-1", "variant-1", "Product", quantity = 1, unitPriceMinor = 1000, taxMinor = 180, discountMinor = 0, lineTotalMinor = 1000, currency = "INR")
    private fun address() = AddressSnapshot("address-1", "Customer", "+919999999999", "1 Main Street", city = "Pune", state = "MH", postalCode = "411001", country = "IN")

    private fun dataSource(): DataSource {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, _ ->
            when (method.name) {
                "setAutoCommit", "rollback", "commit", "close" -> null
                else -> null
            }
        }) as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
            if (method.name == "getConnection") connection else null
        }) as DataSource
    }
}
