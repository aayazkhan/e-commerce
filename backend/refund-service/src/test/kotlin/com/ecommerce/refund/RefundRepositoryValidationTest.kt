package com.ecommerce.refund

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.InternalHttpClient
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefundRepositoryValidationTest {
    private val repository = RefundRepository(dataSource(), InternalHttpClient("http://127.0.0.1:1"), emptyMap())

    @Test
    fun `create rejects missing identifiers and reason`() {
        assertInvalid(RefundRequest("", "payment-1", 100, "INR", RefundType.FULL, "customer request"))
        assertInvalid(RefundRequest("order-1", "", 100, "INR", RefundType.FULL, "customer request"))
        assertInvalid(RefundRequest("order-1", "payment-1", 100, "INR", RefundType.FULL, " "))
    }

    @Test
    fun `create rejects non-positive amount and non-iso currency`() {
        assertInvalid(RefundRequest("order-1", "payment-1", 0, "INR", RefundType.FULL, "customer request"))
        assertInvalid(RefundRequest("order-1", "payment-1", 100, "inr", RefundType.FULL, "customer request"))
        assertInvalid(RefundRequest("order-1", "payment-1", 100, "IN", RefundType.FULL, "customer request"))
    }

    private fun assertInvalid(request: RefundRequest) {
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
