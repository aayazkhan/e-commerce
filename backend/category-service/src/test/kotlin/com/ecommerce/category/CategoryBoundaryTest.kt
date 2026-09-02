package com.ecommerce.category

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CategoryBoundaryTest {
    @Test
    fun `slug accepts lowercase URL-safe values at maximum length`() {
        validateSlug("a".repeat(160))
        validateSlug("electronics-mobile")
    }

    @Test
    fun `slug rejects malformed and oversized values`() {
        listOf("", "Electronics", "two words", "a/child", "a".repeat(161)).forEach { slug ->
            assertFailsWith<IllegalArgumentException> { validateSlug(slug) }
        }
    }
}
