package com.ecommerce.review

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewRepositoryValidationTest {
    @Test
    fun `create rejects missing target order body and invalid rating`() {
        val repository = ReviewRepository(dataSource(), Json.Default, false)
        assertInvalid(repository, ReviewRequest(ReviewTarget.PRODUCT, "", "order-1", 5, body = "great"))
        assertInvalid(repository, ReviewRequest(ReviewTarget.PRODUCT, "product-1", "", 5, body = "great"))
        assertInvalid(repository, ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 0, body = "great"))
        assertInvalid(repository, ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 6, body = "great"))
        assertInvalid(repository, ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 5, body = " "))
    }

    @Test
    fun `report requires a non-blank reason before persistence`() {
        val repository = ReviewRepository(dataSource(), Json.Default, false)
        val error = assertFailsWith<ApiException> { repository.report("user-1", "review-1", ReportRequest(" ")) }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun assertInvalid(repository: ReviewRepository, request: ReviewRequest) {
        val error = assertFailsWith<ApiException> { repository.create("user-1", request) }
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
