package com.ecommerce.platform.common

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class PaginationTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `rejects unbounded page size`() {
        assertFailsWith<IllegalArgumentException> {
            CursorPageRequest(limit = 101)
        }
    }

    @Test
    fun `accepts both page boundaries and preserves cursor`() {
        assertEquals(1, CursorPageRequest(limit = 1).limit)
        assertEquals(CursorPageRequest("cursor-1", CursorPageRequest.MAX_LIMIT), CursorPageRequest("cursor-1", CursorPageRequest.MAX_LIMIT))
        assertFailsWith<IllegalArgumentException> { CursorPageRequest(limit = 0) }
    }

    @Test
    fun `pagination contracts round trip defaults cursors and page state`() {
        val defaultRequest = CursorPageRequest()
        assertEquals(defaultRequest, json.decodeFromString<CursorPageRequest>(json.encodeToString(defaultRequest)))
        val request = CursorPageRequest("cursor-2", 7)
        assertEquals(request, json.decodeFromString<CursorPageRequest>(json.encodeToString(request)))

        val firstPage = CursorPage(listOf("one", "two"), null, hasMore = false)
        val nextPage = CursorPage(listOf("three"), "cursor-3", hasMore = true)
        assertEquals(firstPage, json.decodeFromString<CursorPage<String>>(json.encodeToString(firstPage)))
        assertEquals(nextPage, json.decodeFromString<CursorPage<String>>(json.encodeToString(nextPage)))
        assertEquals(listOf("one", "two"), firstPage.items)
        assertEquals(null, firstPage.nextCursor)
        assertEquals(false, firstPage.hasMore)
        assertEquals("cursor-2", request.cursor)
    }

    @Test
    fun `pagination decoder applies omitted default fields`() {
        assertEquals(CursorPageRequest(), json.decodeFromString<CursorPageRequest>("{}"))
        assertEquals(CursorPageRequest(limit = 7), json.decodeFromString<CursorPageRequest>("{\"limit\":7}"))
        assertEquals(CursorPageRequest(cursor = "next"), json.decodeFromString<CursorPageRequest>("{\"cursor\":\"next\"}"))
        assertEquals(CursorPageRequest(), json.decodeFromString<CursorPageRequest>("{\"cursor\":null}"))

        val compact = Json { encodeDefaults = false; explicitNulls = false }
        assertEquals("{}", compact.encodeToString(CursorPageRequest()))
        assertEquals("{\"cursor\":\"next\",\"limit\":7}", compact.encodeToString(CursorPageRequest("next", 7)))
    }

    @Test
    fun `pagination decoder distinguishes explicit null and rejects incomplete required pages`() {
        val explicitNulls = Json { encodeDefaults = true; explicitNulls = true }
        assertEquals(
            CursorPageRequest(cursor = null, limit = 24),
            explicitNulls.decodeFromString<CursorPageRequest>("{\"cursor\":null,\"limit\":24}"),
        )

        assertFailsWith<SerializationException> {
            json.decodeFromString<CursorPage<String>>("{\"items\":[]}")
        }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<CursorPageRequest>("{\"cursor\":null,\"limit\":0}")
        }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<CursorPageRequest>("{\"cursor\":null,\"limit\":101}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CursorPageRequest>("{\"cursor\":null,\"limit\":null}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CursorPage<String>>("{}")
        }
    }

    @Test
    fun `pagination decoder rejects each individually missing required page field`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<CursorPage<String>>("{\"nextCursor\":null,\"hasMore\":false}")
        }
        assertEquals(
            CursorPage(emptyList(), null, false),
            json.decodeFromString<CursorPage<String>>("{\"items\":[],\"hasMore\":false}"),
        )
        assertFailsWith<SerializationException> {
            json.decodeFromString<CursorPage<String>>("{\"items\":[],\"nextCursor\":null}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CursorPage<String>>("{\"items\":null,\"nextCursor\":null,\"hasMore\":false}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CursorPage<String>>("{\"items\":[],\"nextCursor\":null,\"hasMore\":null}")
        }
    }

    @Test
    fun `pagination copies preserve every default and explicit field`() {
        val request = CursorPageRequest("cursor-1", 7)
        assertEquals(CursorPageRequest(null, 7), request.copy(cursor = null))
        assertEquals(CursorPageRequest("cursor-1", CursorPageRequest.DEFAULT_LIMIT), request.copy(limit = CursorPageRequest.DEFAULT_LIMIT))
        assertEquals(request, request.copy())

        val page = CursorPage(listOf("one"), "cursor-2", hasMore = true)
        assertEquals(CursorPage(emptyList(), "cursor-2", true), page.copy(items = emptyList()))
        assertEquals(CursorPage(listOf("one"), null, true), page.copy(nextCursor = null))
        assertEquals(CursorPage(listOf("one"), "cursor-2", false), page.copy(hasMore = false))
        assertEquals(page, page.copy())
    }

    @Test
    fun `pagination decoder accepts complete pages in a different field order`() {
        assertEquals(
            CursorPageRequest("cursor-7", 9),
            json.decodeFromString<CursorPageRequest>("{\"limit\":9,\"cursor\":\"cursor-7\"}"),
        )
        assertEquals(
            CursorPage(listOf("one"), "cursor-8", true),
            json.decodeFromString<CursorPage<String>>("{\"hasMore\":true,\"nextCursor\":\"cursor-8\",\"items\":[\"one\"]}"),
        )
    }
}
