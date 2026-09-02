package com.ecommerce.cms

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

class CmsRepositoryValidationTest {
    @Test
    fun `create rejects malformed slug blank title and blank content`() {
        val repository = CmsRepository(dataSource(), Json.Default)
        assertInvalid(repository, CmsPageRequest("Bad Slug", "Title", "{}"))
        assertInvalid(repository, CmsPageRequest("valid-slug", " ", "{}"))
        assertInvalid(repository, CmsPageRequest("valid-slug", "Title", ""))
    }

    @Test
    fun `create rejects invalid json content`() {
        val repository = CmsRepository(dataSource(), Json.Default)
        val error = assertFailsWith<ApiException> { repository.create("admin-1", CmsPageRequest("valid-slug", "Title", "{"), "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun assertInvalid(repository: CmsRepository, request: CmsPageRequest) {
        val error = assertFailsWith<ApiException> { repository.create("admin-1", request, "corr-1") }
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
