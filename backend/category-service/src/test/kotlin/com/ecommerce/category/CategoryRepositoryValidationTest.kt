package com.ecommerce.category

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CategoryRepositoryValidationTest {
    @Test
    fun `create rejects invalid name and ordering before persistence`() {
        val repository = CategoryRepository(dataSource())
        val invalidName = input(name = " ")
        val nameError = assertFailsWith<ApiException> { repository.create(invalidName, "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, nameError.errorCode)

        val orderError = assertFailsWith<ApiException> { repository.create(input(sortOrder = -1), "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, orderError.errorCode)
    }

    @Test
    fun `create and update reject invalid slugs and oversized names`() {
        val repository = CategoryRepository(dataSource())
        assertFailsWith<IllegalArgumentException> { repository.create(input(slug = "Bad Slug"), "corr-1") }
        assertFailsWith<IllegalArgumentException> { repository.update("category-1", input(slug = "Bad Slug"), "corr-1") }
        assertFailsWith<ApiException> { repository.create(input(name = "x".repeat(161)), "corr-1") }
    }

    private fun input(name: String = "Footwear", slug: String = "footwear", sortOrder: Int = 0) = CategoryInput(null, name, slug, null, null, null, sortOrder, CategoryStatus.DRAFT, null, null, emptyList(), null)

    @Suppress("UNCHECKED_CAST")
    private fun dataSource(): DataSource {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, _ ->
            when (method.name) {
                "setAutoCommit", "rollback", "commit", "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
            if (method.name == "getConnection") connection else defaultValue(method.returnType)
        }) as DataSource
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }
}
