package com.ecommerce.catalog

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogRepositoryValidationTest {
    @Test
    fun `create rejects missing description and category`() {
        val repository = CatalogRepository(dataSource())
        val descriptionError = assertFailsWith<ApiException> { repository.create(input(description = " "), "seller-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, descriptionError.errorCode)
        val categoryError = assertFailsWith<ApiException> { repository.create(input(categoryId = ""), "seller-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, categoryError.errorCode)
    }

    @Test
    fun `create and update reject invalid product and variant boundaries`() {
        val repository = CatalogRepository(dataSource())
        assertFailsWith<IllegalArgumentException> { repository.create(input(slug = "Bad Slug"), "seller-1", "corr-1") }
        assertFailsWith<ApiException> { repository.create(input(variants = listOf(VariantInput("", null, emptyMap(), null, 100, emptyMap(), VariantStatus.ACTIVE))), "seller-1", "corr-1") }
        assertFailsWith<ApiException> { repository.update("product-1", input(variants = listOf(VariantInput("SKU-1", null, emptyMap(), null, 0, emptyMap(), VariantStatus.ACTIVE))), "seller-1", "corr-1") }
    }

    private fun input(
        categoryId: String = "category-1",
        description: String = "A useful product",
        slug: String = "useful-product",
        variants: List<VariantInput> = listOf(VariantInput("SKU-1", null, emptyMap(), null, 100, emptyMap(), VariantStatus.ACTIVE)),
    ) = ProductInput("seller-1", OwnerType.SELLER, null, categoryId, "Product", slug, description, null, null, ProductStatus.DRAFT, null, emptyMap(), null, null, null, variants, emptyList())

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
