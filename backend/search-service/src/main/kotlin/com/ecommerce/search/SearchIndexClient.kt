package com.ecommerce.search

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

class SearchIndexClient(
    private val baseUrl: String,
    private val alias: String,
    username: String?,
    password: String?,
    timeoutMillis: Long,
    private val transport: ((HttpRequest) -> HttpResponse<String>)? = null,
) : AutoCloseable, SearchIndexWriter, SearchRouteStore {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).build()
    private val auth = if (!username.isNullOrBlank()) "Basic " + Base64.getEncoder().encodeToString("$username:${password.orEmpty()}".toByteArray()) else null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun ping(): Boolean = runCatching { request("GET", "/_cluster/health", null).statusCode() in 200..299 }.getOrDefault(false)

    fun ensureAlias() {
        val response = request("HEAD", "/$alias", null)
        if (response.statusCode() == 200) return
        val index = "$alias-${System.currentTimeMillis()}"
        val body = """{"settings":{"number_of_shards":3,"number_of_replicas":1},"mappings":{"properties":{"name":{"type":"text","fields":{"keyword":{"type":"keyword"}}},"description":{"type":"text"},"shortDescription":{"type":"text"},"slug":{"type":"keyword"},"categoryId":{"type":"keyword"},"brandId":{"type":"keyword"},"status":{"type":"keyword"},"priceMinor":{"type":"long"},"attributes":{"type":"object","dynamic":true},"createdAt":{"type":"date"}}}}"""
        val created = request("PUT", "/$index", body); if (created.statusCode() !in 200..299) dependencyError("Unable to create OpenSearch index")
        val aliasResponse = request("POST", "/_aliases", """{"actions":[{"add":{"index":"$index","alias":"$alias"}}]}"""); if (aliasResponse.statusCode() !in 200..299) dependencyError("Unable to create OpenSearch alias")
    }

    override fun indexProduct(id: String, document: JsonObject) { val response = request("PUT", "/$alias/_doc/$id", json.encodeToString(document)); if (response.statusCode() !in 200..299) dependencyError("Unable to index product") }
    override fun updatePrice(productId: String, variantId: String?, priceMinor: Long, priceVersion: String) { val body = json.encodeToString(buildJsonObject { put("doc", buildJsonObject { put("priceMinor", priceMinor); put("priceVersion", priceVersion); if (variantId != null) put("variantId", variantId) }) }); val response = request("POST", "/$alias/_update/$productId", body); if (response.statusCode() !in 200..299 && response.statusCode() != 404) dependencyError("Unable to update product price") }
    override fun deleteProduct(id: String) { val response = request("DELETE", "/$alias/_doc/$id", null); if (response.statusCode() !in 200..299 && response.statusCode() != 404) dependencyError("Unable to delete indexed product") }

    override fun search(query: String, categoryId: String?, brandId: String?, minPrice: Long?, maxPrice: Long?, sort: String?, from: Int, size: Int): SearchResponse {
        val filters = buildJsonArray {
            categoryId?.let { add(buildJsonObject { put("term", buildJsonObject { put("categoryId", it) }) }) }
            brandId?.let { add(buildJsonObject { put("term", buildJsonObject { put("brandId", it) }) }) }
            if (minPrice != null || maxPrice != null) {
                add(buildJsonObject {
                    put("range", buildJsonObject {
                        put("priceMinor", buildJsonObject {
                            minPrice?.let { put("gte", it) }
                            maxPrice?.let { put("lte", it) }
                        })
                    })
                })
            }
        }
        val must = if (query.isBlank()) buildJsonArray { add(buildJsonObject { put("match_all", buildJsonObject {}) }) } else buildJsonArray { add(buildJsonObject { put("multi_match", buildJsonObject { put("query", query); put("fields", buildJsonArray { add("name^4"); add("description"); add("shortDescription"); add("attributes.*") }); put("fuzziness", "AUTO") }) }) }
        val body = buildJsonObject { put("from", from); put("size", size); put("query", buildJsonObject { put("bool", buildJsonObject { put("must", must); put("filter", filters) }) }); put("sort", sortJson(sort)) ; put("aggs", aggregations()) }
        val response = request("POST", "/$alias/_search", json.encodeToString(body)); if (response.statusCode() !in 200..299) dependencyError("Search backend unavailable"); return parseSearch(response.body())
    }

    override fun suggestions(query: String): SuggestionResponse { if (query.isBlank()) return SuggestionResponse(emptyList()); val body = """{"size":8,"_source":["name"],"query":{"match_phrase_prefix":{"name":${JsonPrimitive(query)}}}}"""; val response = request("POST", "/$alias/_search", body); if (response.statusCode() !in 200..299) dependencyError("Search backend unavailable"); val root = json.parseToJsonElement(response.body()).jsonObject; return SuggestionResponse(root["hits"]?.jsonObject?.get("hits")?.jsonArray.orEmpty().mapNotNull { it.jsonObject["_source"]?.jsonObject?.get("name")?.jsonPrimitive?.content }.distinct()) }
    override fun filters(): FilterResponse { val response = request("POST", "/$alias/_search", """{"size":0,"aggs":{"categories":{"terms":{"field":"categoryId","size":100}},"brands":{"terms":{"field":"brandId","size":100}}}}"""); if (response.statusCode() !in 200..299) dependencyError("Search backend unavailable"); return FilterResponse(parseFacets(json.parseToJsonElement(response.body()).jsonObject)) }

    override fun newReindexIndex(): String { val index = "$alias-${System.currentTimeMillis()}"; val body = """{"settings":{"number_of_shards":3,"number_of_replicas":1},"mappings":{"properties":{"name":{"type":"text","fields":{"keyword":{"type":"keyword"}}},"description":{"type":"text"},"shortDescription":{"type":"text"},"slug":{"type":"keyword"},"categoryId":{"type":"keyword"},"brandId":{"type":"keyword"},"status":{"type":"keyword"},"priceMinor":{"type":"long"},"attributes":{"type":"object","dynamic":true},"createdAt":{"type":"date"}}}}"""; val response = request("PUT", "/$index", body); if (response.statusCode() !in 200..299) dependencyError("Unable to create reindex target"); return index }
    override fun swapAlias(index: String) {
        val current = request("GET", "/_alias/$alias", null)
        val actions = buildJsonArray {
            val oldIndices = runCatching { Json.parseToJsonElement(current.body()).jsonObject.keys }.getOrDefault(emptySet())
            oldIndices.forEach { old -> add(buildJsonObject { put("remove", buildJsonObject { put("index", old); put("alias", alias) }) }) }
            add(buildJsonObject { put("add", buildJsonObject { put("index", index); put("alias", alias) }) })
        }
        val response = request("POST", "/_aliases", json.encodeToString(buildJsonObject { put("actions", actions) }))
        if (response.statusCode() !in 200..299) dependencyError("Unable to swap search alias")
    }
    override fun indexInto(index: String, id: String, document: JsonObject) { val response = request("PUT", "/$index/_doc/$id", json.encodeToString(document)); if (response.statusCode() !in 200..299) dependencyError("Unable to write reindex document") }

    private fun sortJson(sort: String?): JsonElement = when (sort) { "price_asc" -> buildJsonArray { add(buildJsonObject { put("priceMinor", "asc") }) }; "price_desc" -> buildJsonArray { add(buildJsonObject { put("priceMinor", "desc") }) }; "newest" -> buildJsonArray { add(buildJsonObject { put("createdAt", "desc") }) }; else -> buildJsonArray { add("_score"); add("_id") } }
    private fun aggregations() = buildJsonObject { put("categories", buildJsonObject { put("terms", buildJsonObject { put("field", "categoryId"); put("size", 100) }) }); put("brands", buildJsonObject { put("terms", buildJsonObject { put("field", "brandId"); put("size", 100) }) }) }
    private fun parseSearch(body: String): SearchResponse { val root = json.parseToJsonElement(body).jsonObject; val hits = root["hits"]?.jsonObject; val items = hits?.get("hits")?.jsonArray.orEmpty().map { hit -> val obj = hit.jsonObject; SearchHit(obj["_id"]!!.jsonPrimitive.content, obj["_score"]?.jsonPrimitive?.doubleOrNull, obj["_source"]?.jsonObject ?: buildJsonObject {}) }; return SearchResponse(items, hits?.get("total")?.jsonObject?.get("value")?.jsonPrimitive?.longOrNull ?: items.size.toLong(), root["took"]?.jsonPrimitive?.longOrNull ?: 0, parseFacets(root)) }
    private fun parseFacets(root: JsonObject): Map<String, Map<String, Long>> = root["aggregations"]?.jsonObject?.mapValues { (_, value) -> value.jsonObject["buckets"]?.jsonArray.orEmpty().associate { bucket -> bucket.jsonObject["key"]!!.jsonPrimitive.content to (bucket.jsonObject["doc_count"]?.jsonPrimitive?.longOrNull ?: 0) } } ?: emptyMap()
    private fun request(method: String, path: String, body: String?): HttpResponse<String> { val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(5)).header("Accept", "application/json"); auth?.let { builder.header("Authorization", it) }; if (body != null) builder.header("Content-Type", "application/json"); val request = when (method) { "GET" -> builder.GET().build(); "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build(); "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(); "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(); "DELETE" -> builder.DELETE().build(); else -> error("Unsupported method") }; return runCatching { transport?.invoke(request) ?: client.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrElse { dependencyError("Search backend unavailable") } }
    private fun dependencyError(message: String): Nothing = throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, message, 503, retryable = true)
    override fun close() = Unit
}
