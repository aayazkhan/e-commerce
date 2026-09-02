package com.ecommerce.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SearchHit(val id: String, val score: Double?, val source: JsonObject)

@Serializable
data class SearchResponse(val items: List<SearchHit>, val total: Long, val tookMillis: Long, val facets: Map<String, Map<String, Long>> = emptyMap())

@Serializable
data class SuggestionResponse(val suggestions: List<String>)

@Serializable
data class FilterResponse(val facets: Map<String, Map<String, Long>>)
