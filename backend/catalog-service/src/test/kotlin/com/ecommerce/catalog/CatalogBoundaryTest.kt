package com.ecommerce.catalog

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CatalogBoundaryTest {
    @Test
    fun `product name accepts trimmed boundary and rejects blank or oversized input`() {
        validateProductName("Phone")
        validateProductName(" ${"a".repeat(255)} ")
        assertFailsWith<IllegalArgumentException> { validateProductName("") }
        assertFailsWith<IllegalArgumentException> { validateProductName(" ") }
        assertFailsWith<IllegalArgumentException> { validateProductName("a".repeat(256)) }
    }

    @Test
    fun `product slug accepts only lowercase URL-safe text`() {
        validateProductSlug("phone-1")
        listOf("Phone-1", "phone 1", "phone_1", "").forEach { value ->
            assertFailsWith<IllegalArgumentException> { validateProductSlug(value) }
        }
    }
}
