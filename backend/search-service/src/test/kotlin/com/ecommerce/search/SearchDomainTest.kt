package com.ecommerce.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

class SearchDomainTest {
    @Test fun `search hit preserves source`() { val source = buildJsonObject { put("name", JsonPrimitive("Phone")) }; assertEquals("Phone", SearchHit("p1", 1.0, source).source["name"]?.toString()?.trim('"')) }

    @Test
    fun `compact search responses preserve nullable scores and empty facets`() {
        val json = Json { explicitNulls = false }
        val hit = SearchHit("p1", null, buildJsonObject { put("name", JsonPrimitive("Phone")) })
        val response = SearchResponse(listOf(hit), 1, 3)
        val suggestions = SuggestionResponse(emptyList())
        val filters = FilterResponse(emptyMap())
        assertEquals(hit, json.decodeFromString(SearchHit.serializer(), json.encodeToString(SearchHit.serializer(), hit)))
        assertEquals(response, json.decodeFromString(SearchResponse.serializer(), json.encodeToString(SearchResponse.serializer(), response)))
        assertEquals(suggestions, json.decodeFromString(SuggestionResponse.serializer(), json.encodeToString(SuggestionResponse.serializer(), suggestions)))
        assertEquals(filters, json.decodeFromString(FilterResponse.serializer(), json.encodeToString(FilterResponse.serializer(), filters)))
    }
}
