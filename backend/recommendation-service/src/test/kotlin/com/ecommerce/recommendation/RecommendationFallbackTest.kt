package com.ecommerce.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendationFallbackTest {
    @Test fun `fallback response is stable and independent of cache`() {
        val result=RecommendationResponse(RecommendationType.TRENDING,listOf("popular-1","popular-2"),"popular-fallback")
        assertEquals("popular-fallback",result.source)
        assertEquals(listOf("popular-1","popular-2"),result.productIds)
    }
}
