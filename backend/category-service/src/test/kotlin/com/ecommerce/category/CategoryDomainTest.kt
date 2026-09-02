package com.ecommerce.category

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CategoryDomainTest {
    @Test fun `slugs are URL safe`() { validateSlug("electronics-mobile"); assertFailsWith<IllegalArgumentException> { validateSlug("Electronics Mobile") } }
}
