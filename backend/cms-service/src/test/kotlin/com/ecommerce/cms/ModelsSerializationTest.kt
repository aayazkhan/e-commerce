package com.ecommerce.cms

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `cms request serializers cover default and complete SEO values`() {
        val defaults = CmsPageRequest("home", "Home", "{}")
        assertEquals(defaults, json.decodeFromString<CmsPageRequest>(json.encodeToString(defaults)))
        assertEquals(defaults, json.decodeFromString<CmsPageRequest>("{\"slug\":\"home\",\"title\":\"Home\",\"contentJson\":\"{}\"}"))
        assertEquals(CmsPublishRequest(), json.decodeFromString<CmsPublishRequest>(json.encodeToString(CmsPublishRequest())))
        assertEquals(CmsPublishRequest(), json.decodeFromString<CmsPublishRequest>("{}"))

        val seo = SeoMetadata(
            title = "Home",
            metaTitle = "Home page",
            metaDescription = "A useful home page",
            canonicalUrl = "https://shop.test/home",
            slug = "home",
            robots = "index,follow",
            structuredData = "{}",
            ogTitle = "Home",
            ogDescription = "Welcome",
            ogImage = "https://cdn.test/home.png"
        )
        val complete = CmsPageRequest("home", "Home", "{\"body\":\"content\"}", seo, 3)
        assertEquals(complete, json.decodeFromString<CmsPageRequest>(json.encodeToString(complete)))
        assertEquals(CmsPublishRequest(2, "2026-08-21T10:00:00Z", "2026-08-22T10:00:00Z", "Asia/Kolkata"), json.decodeFromString<CmsPublishRequest>(json.encodeToString(CmsPublishRequest(2, "2026-08-21T10:00:00Z", "2026-08-22T10:00:00Z", "Asia/Kolkata"))))
        assertEquals(CmsRollbackRequest(2, 3), json.decodeFromString<CmsRollbackRequest>(json.encodeToString(CmsRollbackRequest(2, 3))))
    }

    @Test
    fun `cms response serializer covers every lifecycle status`() {
        val seo = SeoMetadata()
        CmsStatus.entries.forEach { status ->
            val response = CmsPageResponse("page-1", "home", "Home", status, 2, 3, "{}", seo, "admin-1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
            assertEquals(response, json.decodeFromString<CmsPageResponse>(json.encodeToString(response)))
        }
    }

    @Test
    fun `compact CMS serialization preserves empty SEO and schedule defaults`() {
        val seo = SeoMetadata()
        val page = CmsPageRequest("home", "Home", "{}")
        val publish = CmsPublishRequest()
        assertEquals(seo, compactJson.decodeFromString(SeoMetadata.serializer(), compactJson.encodeToString(SeoMetadata.serializer(), seo)))
        assertEquals(page, compactJson.decodeFromString(CmsPageRequest.serializer(), compactJson.encodeToString(CmsPageRequest.serializer(), page)))
        assertEquals(publish, compactJson.decodeFromString(CmsPublishRequest.serializer(), compactJson.encodeToString(CmsPublishRequest.serializer(), publish)))
        assertEquals(CmsRollbackRequest(1, 1), compactJson.decodeFromString(CmsRollbackRequest.serializer(), compactJson.encodeToString(CmsRollbackRequest.serializer(), CmsRollbackRequest(1, 1))))
        listOf(
            publish.copy(version = 2),
            publish.copy(publishAt = "2026-08-21T10:00:00Z"),
            publish.copy(unpublishAt = "2026-08-22T10:00:00Z"),
            publish.copy(timezone = "Asia/Kolkata"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(CmsPublishRequest.serializer(), compactJson.encodeToString(CmsPublishRequest.serializer(), value)))
        }

        listOf(
            seo.copy(title = "Home"),
            seo.copy(metaTitle = "Home page"),
            seo.copy(metaDescription = "A useful home page"),
            seo.copy(canonicalUrl = "https://shop.test/home"),
            seo.copy(slug = "home"),
            seo.copy(robots = "index,follow"),
            seo.copy(structuredData = "{}"),
            seo.copy(ogTitle = "Home"),
            seo.copy(ogDescription = "Welcome"),
            seo.copy(ogImage = "https://cdn.test/home.png"),
        ).forEach { variant ->
            assertEquals(
                variant,
                compactJson.decodeFromString(SeoMetadata.serializer(), compactJson.encodeToString(SeoMetadata.serializer(), variant)),
            )
        }
    }

    @Test
    fun `data class equality is reflexive and detects field level differences`() {
        val seo = SeoMetadata(title = "Home")
        assertTrue(seo == seo)
        assertNotEquals(seo, seo.copy(title = "Other"))
        assertNotEquals(seo, SeoMetadata())

        val request = CmsPageRequest("home", "Home", "{}", seo, 3)
        assertTrue(request == request)
        assertNotEquals(request, request.copy(slug = "other"))
        assertNotEquals(request, request.copy(expectedVersion = null))
        assertEquals(request.hashCode(), request.copy().hashCode())

        val publish = CmsPublishRequest(1, "2026-08-21T10:00:00Z", "2026-08-22T10:00:00Z", "Asia/Kolkata")
        assertTrue(publish == publish)
        assertNotEquals(publish, publish.copy(version = 2))

        val rollback = CmsRollbackRequest(1, 2)
        assertTrue(rollback == rollback)
        assertNotEquals(rollback, rollback.copy(version = 2))
        assertNotEquals(rollback, rollback.copy(expectedVersion = 3))

        val response = CmsPageResponse("page-1", "home", "Home", CmsStatus.DRAFT, 1, 1, "{}", seo, "admin-1", "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        assertTrue(response == response)
        assertNotEquals(response, response.copy(status = CmsStatus.PUBLISHED))
        assertNotEquals<Any?>(response, "not-a-response")
    }

    @Test
    fun `cms page request tolerates an explicit null expected version`() {
        val decoded = json.decodeFromString<CmsPageRequest>("{\"slug\":\"home\",\"title\":\"Home\",\"contentJson\":\"{}\",\"expectedVersion\":null}")
        assertEquals(CmsPageRequest("home", "Home", "{}"), decoded)
    }
}
