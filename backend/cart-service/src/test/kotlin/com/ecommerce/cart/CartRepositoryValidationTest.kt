package com.ecommerce.cart

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CartRepositoryValidationTest {
    @Test
    fun `cart actor requires exactly one ownership identity`() {
        assertFailsWith<IllegalArgumentException> { CartActor() }
        assertFailsWith<IllegalArgumentException> { CartActor("user-1", "guest-token") }
        assertFailsWith<IllegalArgumentException> { CartActor(guestToken = "") }
        assertEquals(CartActor(userId = "user-1"), CartActor(userId = "user-1"))
        assertEquals(CartActor(guestToken = "guest-token"), CartActor(guestToken = "guest-token"))
    }

    @Test
    fun `mutations require a bounded idempotency key before database lookup`() {
        val repository = CartRepository(dataSource())
        val price = PriceSnapshot(1_000, "INR", "v1")
        val blank = assertFailsWith<ApiException> { repository.add(CartActor(userId = "user-1"), "product-1", "variant-1", 1, price, " ", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, blank.errorCode)
        val oversized = assertFailsWith<ApiException> { repository.add(CartActor(userId = "user-1"), "product-1", "variant-1", 1, price, "x".repeat(129), "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, oversized.errorCode)
    }

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
