package com.ecommerce.search

import com.ecommerce.platform.error.ApiException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.Certificate
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchIndexClientTest {
    private val response = """
        {"took":4,"hits":{"total":{"value":2},"hits":[{"_id":"p1","_score":1.5,"_source":{"name":"Phone"}},{"_id":"p2","_source":{"name":"Tablet"}}]},"aggregations":{"categories":{"buckets":[{"key":"electronics","doc_count":2}]},"brands":{"buckets":[{"key":"acme","doc_count":1}]}}}
    """.trimIndent()

    @Test
    fun `search client covers health query writes suggestions filters and reindex flow`() {
        val server = server { exchange ->
            when {
                exchange.requestMethod == "HEAD" && exchange.requestURI.path == "/products" -> exchange.respond(200, "")
                exchange.requestURI.path == "/_alias/products" -> exchange.respond(200, "{\"products-2025\":{}}")
                exchange.requestURI.path.endsWith("/_search") -> exchange.respond(200, response)
                else -> exchange.respond(200, "{}")
            }
        }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}", "products", "user", "password", 1000)
            assertTrue(client.ping())
            client.ensureAlias()
            val document = buildJsonObject { put("name", "Phone") }
            client.indexProduct("p1", document)
            client.updatePrice("p1", "v1", 999, "price-2")
            client.updatePrice("p1", null, 899, "price-3")
            client.deleteProduct("p1")

            listOf(null, "price_asc", "price_desc", "newest", "unsupported").forEach { sort ->
                val result = client.search("phone", "electronics", "acme", 100, 2000, sort, 0, 20)
                assertEquals(2, result.total)
                assertEquals(listOf("p1", "p2"), result.items.map { it.id })
                assertEquals(2, result.facets["categories"]?.get("electronics"))
            }
            val blankQuery = client.search("", null, null, null, null, null, 0, 10)
            assertEquals(2, blankQuery.items.size)
            assertEquals(SuggestionResponse(listOf("Phone", "Tablet")), client.suggestions("ph"))
            assertEquals(SuggestionResponse(emptyList()), client.suggestions("  "))
            assertEquals(FilterResponse(mapOf("categories" to mapOf("electronics" to 2L), "brands" to mapOf("acme" to 1L))), client.filters())

            val index = client.newReindexIndex()
            assertTrue(index.startsWith("products-"))
            client.swapAlias(index)
            client.indexInto(index, "p1", document)
            client.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ensure alias creates missing index and alias`() {
        val server = server { exchange ->
            if (exchange.requestMethod == "HEAD") exchange.respond(404, "") else exchange.respond(200, "{}")
        }
        try {
            SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000).ensureAlias()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ensure alias accepts successful index and alias creation responses`() {
        val requests = mutableListOf<String>()
        val server = server { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestMethod) {
                "HEAD" -> exchange.respond(404, "")
                "PUT", "POST" -> exchange.respond(299, "{}")
                else -> exchange.respond(500, "unexpected")
            }
        }
        try {
            SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000).ensureAlias()
            assertEquals(listOf("HEAD /products", "PUT /products-" + requests[1].substringAfter("/products-"), "POST /_aliases"), requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ensure alias does not mutate an already existing alias`() {
        val requests = mutableListOf<String>()
        val server = server { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            if (exchange.requestMethod == "HEAD") exchange.respond(200, "") else exchange.respond(500, "unexpected")
        }
        try {
            SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000).ensureAlias()
            assertEquals(listOf("HEAD /products"), requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `search client maps backend failures to dependency errors`() {
        val server = server { exchange -> exchange.respond(500, "failed") }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000)
            assertFalse(client.ping())
            assertFailsWith<ApiException> { client.indexProduct("p1", buildJsonObject {}) }
            assertFailsWith<ApiException> { client.search("phone", null, null, null, null, null, 0, 10) }
            assertFailsWith<ApiException> { client.suggestions("phone") }
            assertFailsWith<ApiException> { client.filters() }
        } finally {
            server.stop(0)
        }
        assertFalse(SearchIndexClient("http://localhost:1", "products", null, null, 1000).ping())
    }

    @Test
    fun `search client covers optional response fields, partial ranges, tolerated deletes and alias fallback`() {
        val server = server { exchange ->
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            when {
                exchange.requestURI.path == "/products/_search" && requestBody.contains("match_phrase_prefix") -> exchange.respond(200, "{\"hits\":{\"hits\":[{\"_source\":{}},{\"_source\":{\"name\":\"Phone\"}},{\"_source\":{\"name\":\"Phone\"}}]}}")
                exchange.requestURI.path == "/products/_search" && requestBody.startsWith("{\"size\":0") -> exchange.respond(200, "{\"hits\":{\"hits\":[]}}")
                exchange.requestURI.path == "/products/_search" && exchange.requestMethod == "POST" -> exchange.respond(200, "{\"hits\":{\"hits\":[{\"_id\":\"p1\"}]}}")
                exchange.requestURI.path == "/products/_update/p1" -> exchange.respond(404, "missing")
                exchange.requestURI.path == "/products/_doc/p1" && exchange.requestMethod == "DELETE" -> exchange.respond(404, "missing")
                exchange.requestURI.path == "/_alias/products" -> exchange.respond(200, "not-json")
                else -> exchange.respond(200, "{}")
            }
        }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000)
            assertEquals(1, client.search("phone", null, null, 100, null, null, 0, 10).items.size)
            assertEquals(1, client.search("phone", null, null, null, 2000, null, 0, 10).items.size)
            client.updatePrice("p1", null, 100, "v1")
            client.deleteProduct("p1")
            assertEquals(SuggestionResponse(listOf("Phone")), client.suggestions("phone"))
            assertEquals(FilterResponse(emptyMap()), client.filters())
            client.swapAlias("products-next")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `search client reports index and alias creation failures`() {
        val createIndexFailure = server { exchange -> if (exchange.requestMethod == "HEAD") exchange.respond(404, "") else exchange.respond(500, "failed") }
        try {
            assertFailsWith<ApiException> { SearchIndexClient("http://localhost:${createIndexFailure.address.port}", "products", null, null, 1000).ensureAlias() }
        } finally {
            createIndexFailure.stop(0)
        }

        val aliasFailure = server { exchange ->
            when {
                exchange.requestMethod == "HEAD" -> exchange.respond(404, "")
                exchange.requestMethod == "PUT" -> exchange.respond(200, "{}")
                else -> exchange.respond(500, "failed")
            }
        }
        try {
            assertFailsWith<ApiException> { SearchIndexClient("http://localhost:${aliasFailure.address.port}", "products", null, null, 1000).ensureAlias() }
        } finally {
            aliasFailure.stop(0)
        }
    }

    @Test
    fun `search client covers tolerated and fatal provider outcomes plus parser defaults`() {
        val server = server { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            when {
                exchange.requestURI.path == "/products/_update/p1" -> exchange.respond(500, "failed")
                exchange.requestURI.path == "/products/_doc/p1" && exchange.requestMethod == "DELETE" -> exchange.respond(500, "failed")
                exchange.requestURI.path == "/products/_search" && body.contains("match_phrase_prefix") -> exchange.respond(200, "{}")
                exchange.requestURI.path == "/products/_search" && body.startsWith("{\"size\":0") -> exchange.respond(200, "{\"aggregations\":{\"categories\":{\"buckets\":[{\"key\":\"empty-count\"}]}}}")
                exchange.requestURI.path == "/products/_search" -> exchange.respond(200, "{}")
                exchange.requestURI.path == "/_alias/products" && exchange.requestMethod == "GET" -> exchange.respond(200, "{}")
                exchange.requestURI.path == "/_aliases" && exchange.requestMethod == "POST" -> exchange.respond(500, "failed")
                exchange.requestURI.path.startsWith("/products-") && exchange.requestMethod == "PUT" -> exchange.respond(500, "failed")
                exchange.requestURI.path.startsWith("/products-next/") -> exchange.respond(500, "failed")
                else -> exchange.respond(200, "{}")
            }
        }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}/", "products", " ", null, 1000)
            assertTrue(client.ping())
            assertFailsWith<ApiException> { client.updatePrice("p1", null, 100, "v1") }
            assertFailsWith<ApiException> { client.deleteProduct("p1") }
            assertEquals(0, client.search("empty", null, null, null, null, null, 0, 10).total)
            assertEquals(SuggestionResponse(emptyList()), client.suggestions("empty"))
            assertEquals(0L, client.filters().facets.getValue("categories").getValue("empty-count"))
            assertFailsWith<ApiException> { client.newReindexIndex() }
            assertFailsWith<ApiException> { client.swapAlias("products-next") }
            assertFailsWith<ApiException> { client.indexInto("products-next", "p1", buildJsonObject {}) }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `search client handles inclusive success and exclusive redirect status boundaries`() {
        val server = server { exchange ->
            when {
                exchange.requestMethod == "HEAD" && exchange.requestURI.path == "/products" -> exchange.respond(404, "")
                exchange.requestURI.path == "/_cluster/health" -> exchange.respond(299, "{}")
                exchange.requestURI.path.startsWith("/products-") && exchange.requestMethod == "PUT" -> exchange.respond(299, "{}")
                exchange.requestURI.path == "/_aliases" -> exchange.respond(299, "{}")
                exchange.requestURI.path == "/products/_doc/p1" && exchange.requestMethod == "PUT" -> exchange.respond(299, "{}")
                exchange.requestURI.path == "/products/_update/p1" -> exchange.respond(300, "redirect")
                exchange.requestURI.path == "/products/_doc/p1" && exchange.requestMethod == "DELETE" -> exchange.respond(300, "redirect")
                exchange.requestURI.path == "/products/_search" -> exchange.respond(300, "redirect")
                exchange.requestURI.path == "/_alias/products" -> exchange.respond(200, "{}")
                exchange.requestURI.path.startsWith("/products-next/") -> exchange.respond(299, "{}")
                else -> exchange.respond(200, "{}")
            }
        }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}", "products", "user", null, 1000)
            assertTrue(client.ping())
            client.ensureAlias()
            client.indexProduct("p1", buildJsonObject {})
            assertFailsWith<ApiException> { client.updatePrice("p1", null, 1, "v1") }
            assertFailsWith<ApiException> { client.deleteProduct("p1") }
            assertFailsWith<ApiException> { client.search("phone", null, null, null, null, null, 0, 10) }
            assertFailsWith<ApiException> { client.suggestions("phone") }
            assertFailsWith<ApiException> { client.filters() }
            assertTrue(client.newReindexIndex().startsWith("products-"))
            client.swapAlias("products-next")
            client.indexInto("products-next", "p1", buildJsonObject {})
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `search parser handles missing totals empty hits and aggregation buckets`() {
        val server = server { exchange ->
            when {
                exchange.requestURI.path == "/products/_search" && exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8).contains("match_phrase_prefix") -> exchange.respond(200, "{\"hits\":{\"hits\":[]}}")
                exchange.requestURI.path == "/products/_search" -> exchange.respond(200, "{\"hits\":{\"total\":{\"value\":\"not-a-number\"},\"hits\":[]},\"aggregations\":{\"categories\":{}}}")
                else -> exchange.respond(200, "{}")
            }
        }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000)
            assertEquals(0L, client.search("phone", null, null, null, null, null, 0, 10).total)
            assertEquals(0L, client.search("phone", null, null, null, null, null, 0, 10).tookMillis)
            assertEquals(SuggestionResponse(emptyList()), client.suggestions("phone"))
            assertEquals(FilterResponse(mapOf("categories" to emptyMap())), client.filters())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `search query builds one-sided price ranges without inventing the other bound`() {
        val bodies = mutableListOf<String>()
        val server = server { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            exchange.respond(200, "{\"hits\":{\"hits\":[]}}")
        }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000)

            client.search("phone", null, null, minPrice = 100, maxPrice = null, sort = null, from = 0, size = 10)
            client.search("phone", null, null, minPrice = null, maxPrice = 2_000, sort = null, from = 0, size = 10)

            assertTrue(bodies[0].contains("\"gte\":100"))
            assertTrue(!bodies[0].contains("\"lte\""))
            assertTrue(bodies[1].contains("\"lte\":2000"))
            assertTrue(!bodies[1].contains("\"gte\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `search query emits each optional filter independently and as a full range`() {
        val bodies = mutableListOf<String>()
        val server = server { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            exchange.respond(200, "{\"hits\":{\"hits\":[]}}")
        }
        try {
            val client = SearchIndexClient("http://localhost:${server.address.port}", "products", null, null, 1000)

            client.search("phone", categoryId = "electronics", brandId = null, minPrice = null, maxPrice = null, sort = null, from = 0, size = 10)
            client.search("phone", categoryId = null, brandId = "acme", minPrice = null, maxPrice = null, sort = null, from = 0, size = 10)
            client.search("phone", categoryId = null, brandId = null, minPrice = 100, maxPrice = 2_000, sort = null, from = 0, size = 10)

            assertTrue(bodies[0].contains("categoryId"))
            assertTrue(!bodies[0].contains("\"term\":{\"brandId\""))
            assertTrue(bodies[1].contains("brandId"))
            assertTrue(!bodies[1].contains("\"term\":{\"categoryId\""))
            assertTrue(bodies[2].contains("\"gte\":100"))
            assertTrue(bodies[2].contains("\"lte\":2000"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `search client rejects responses below the successful status range`() {
        val client = SearchIndexClient("http://search.test", "products", null, null, 1000) { response(199, "early") }

        assertFalse(client.ping())
        assertFailsWith<ApiException> { client.ensureAlias() }
        assertFailsWith<ApiException> { client.indexProduct("p1", buildJsonObject {}) }
        assertFailsWith<ApiException> { client.updatePrice("p1", null, 100, "v1") }
        assertFailsWith<ApiException> { client.deleteProduct("p1") }
        assertFailsWith<ApiException> { client.search("phone", null, null, null, null, null, 0, 10) }
        assertFailsWith<ApiException> { client.suggestions("phone") }
        assertFailsWith<ApiException> { client.filters() }
        assertFailsWith<ApiException> { client.newReindexIndex() }
        assertFailsWith<ApiException> { client.swapAlias("products-next") }
        assertFailsWith<ApiException> { client.indexInto("products-next", "p1", buildJsonObject {}) }
    }

    @Test
    fun `search adapter covers strict status boundaries and sparse provider response shapes`() {
        val missingIndex = SearchIndexClient("http://search.test", "products", null, null, 1000) { request ->
            when (request.method()) {
                "HEAD" -> response(404, "")
                "PUT" -> response(300, "redirect")
                else -> response(200, "{}")
            }
        }
        assertFailsWith<ApiException> { missingIndex.ensureAlias() }

        val missingAlias = SearchIndexClient("http://search.test", "products", null, null, 1000) { request ->
            when (request.method()) {
                "HEAD" -> response(404, "")
                "PUT" -> response(200, "{}")
                "POST" -> response(300, "redirect")
                else -> response(200, "{}")
            }
        }
        assertFailsWith<ApiException> { missingAlias.ensureAlias() }
        assertFailsWith<ApiException> {
            SearchIndexClient("http://search.test", "products", null, null, 1000) { response(300, "redirect") }
                .indexProduct("p1", buildJsonObject {})
        }

        assertFailsWith<ApiException> {
            SearchIndexClient("http://search.test", "products", null, null, 1000) { response(199, "early") }
                .updatePrice("p1", null, 100, "v1")
        }
        assertFailsWith<ApiException> {
            SearchIndexClient("http://search.test", "products", null, null, 1000) { response(199, "early") }
                .deleteProduct("p1")
        }

        assertEquals(
            SuggestionResponse(emptyList()),
            SearchIndexClient("http://search.test", "products", null, null, 1000) { response(299, "{}") }.suggestions("phone"),
        )
        assertEquals(
            FilterResponse(emptyMap()),
            SearchIndexClient("http://search.test", "products", null, null, 1000) { response(299, "{\"aggregations\":{}}") }.filters(),
        )
        assertEquals(
            SuggestionResponse(emptyList()),
            SearchIndexClient("http://search.test", "products", null, null, 1000) { response(200, "{\"hits\":{}}") }.suggestions("phone"),
        )
        val sparseSearch = SearchIndexClient("http://search.test", "products", null, null, 1000) { response(200, "{\"hits\":{}}") }
        assertEquals(0L, sparseSearch.search("phone", null, null, null, null, null, 0, 10).total)
    }

    @Test
    fun `ensure alias rejects index and alias responses below the successful range`() {
        assertFailsWith<ApiException> {
            SearchIndexClient("http://search.test", "products", null, null, 1000) { request ->
                when (request.method()) {
                    "HEAD" -> response(404, "")
                    "PUT" -> response(199, "early")
                    else -> response(200, "{}")
                }
            }.ensureAlias()
        }

        assertFailsWith<ApiException> {
            SearchIndexClient("http://search.test", "products", null, null, 1000) { request ->
                when (request.method()) {
                    "HEAD" -> response(404, "")
                    "PUT" -> response(200, "{}")
                    "POST" -> response(199, "early")
                    else -> response(200, "{}")
                }
            }.ensureAlias()
        }
    }

    private fun response(status: Int, body: String): HttpResponse<String> = object : HttpResponse<String> {
        override fun statusCode() = status
        override fun request() = null
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers() = HttpHeaders.of(emptyMap(), { _, _ -> true })
        override fun body() = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri() = URI.create("http://search.test")
        override fun version() = HttpClient.Version.HTTP_1_1
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).also {
        it.createContext("/") { exchange -> handler(exchange) }
        it.start()
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, if (requestMethod == "HEAD") -1 else bytes.size.toLong())
        if (requestMethod != "HEAD") responseBody.use { it.write(bytes) }
    }
}
