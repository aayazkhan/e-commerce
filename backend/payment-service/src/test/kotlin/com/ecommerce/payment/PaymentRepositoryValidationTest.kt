package com.ecommerce.payment

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaymentRepositoryValidationTest {
    @Test
    fun `create rejects missing order and payment token`() {
        val repository = PaymentRepository(dataSource(), emptyMap())
        assertInvalid(repository, PaymentCreateRequest("", 1000, "INR", PaymentProviderName.HTTP, "token"))
        assertInvalid(repository, PaymentCreateRequest("order-1", 1000, "INR", PaymentProviderName.HTTP, ""))
    }

    @Test
    fun `create rejects non-positive amount and malformed currency`() {
        val repository = PaymentRepository(dataSource(), emptyMap())
        assertInvalid(repository, PaymentCreateRequest("order-1", 0, "INR", PaymentProviderName.HTTP, "token"))
        assertInvalid(repository, PaymentCreateRequest("order-1", 1000, "inr", PaymentProviderName.HTTP, "token"))
        assertInvalid(repository, PaymentCreateRequest("order-1", 1000, "IN", PaymentProviderName.HTTP, "token"))
    }

    private fun assertInvalid(repository: PaymentRepository, request: PaymentCreateRequest) {
        val error = assertFailsWith<ApiException> { repository.create("user-1", request, "key-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
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
