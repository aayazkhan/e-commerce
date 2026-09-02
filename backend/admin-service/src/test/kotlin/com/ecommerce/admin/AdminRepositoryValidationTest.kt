package com.ecommerce.admin

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

class AdminRepositoryValidationTest {
    @Test
    fun `bulk job requires distinct non-blank items`() {
        val repository = AdminRepository(dataSource(), Json.Default)
        assertInvalid(repository, BulkJobRequest(emptyList()))
        assertInvalid(repository, BulkJobRequest(listOf("item-1", " ")))
        assertInvalid(repository, BulkJobRequest(List(10_001) { "item-$it" }))
    }

    @Test
    fun `bulk job payload must be valid json`() {
        val repository = AdminRepository(dataSource(), Json.Default)
        val error = assertFailsWith<ApiException> { repository.createJob("PRODUCT_PUBLISH", "admin-1", BulkJobRequest(listOf("item-1"), "{"), "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun assertInvalid(repository: AdminRepository, request: BulkJobRequest) {
        val error = assertFailsWith<ApiException> { repository.createJob("PRODUCT_PUBLISH", "admin-1", request, "corr-1") }
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
