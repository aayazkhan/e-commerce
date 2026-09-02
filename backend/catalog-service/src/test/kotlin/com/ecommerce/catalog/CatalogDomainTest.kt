package com.ecommerce.catalog

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CatalogDomainTest {
    @Test fun `product slug and name validation reject unsafe values`() { validateProductName("Phone"); validateProductSlug("phone-1"); assertFailsWith<IllegalArgumentException> { validateProductSlug("Phone 1") }; assertFailsWith<IllegalArgumentException> { validateProductName("") } }
}
