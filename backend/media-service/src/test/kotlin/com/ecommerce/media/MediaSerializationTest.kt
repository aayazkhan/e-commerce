package com.ecommerce.media

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `media asset variants and event payload round trip`() {
        MediaStatus.entries.forEach { status ->
            val variant = MediaVariant("variant-1", "media-1", "THUMBNAIL", "media/thumb.jpg", "image/jpeg", 5120, 120, 120, "https://cdn.test/thumb.jpg")
            val asset = MediaAsset("media-1", "owner-1", "media/original.jpg", "media", "original.jpg", "image/jpeg", 10240, "checksum", 1000, 1000, status, "2026-08-20T10:00:00Z", "2026-08-20T10:01:00Z", listOf(variant))
            assertEquals(asset, json.decodeFromString<MediaAsset>(json.encodeToString(asset)))
        }
        val event = MediaEventPayload("media-1", "READY", "media/original.jpg")
        assertEquals(event, json.decodeFromString<MediaEventPayload>(json.encodeToString(event)))
    }

    @Test
    fun `compact media serialization preserves nullable metadata and empty variants`() {
        val asset = MediaAsset("media-1", "owner-1", "media/original", "media", null, "image/png", 1, "checksum", null, null, MediaStatus.PENDING_UPLOAD, "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z")
        val variant = MediaVariant("variant-1", "media-1", "THUMBNAIL", "media/thumb", "image/png", 1, null, null)
        val event = MediaEventPayload("media-1", "PENDING_UPLOAD", "media/original")
        assertEquals(asset, compactJson.decodeFromString(MediaAsset.serializer(), compactJson.encodeToString(MediaAsset.serializer(), asset)))
        assertEquals(variant, compactJson.decodeFromString(MediaVariant.serializer(), compactJson.encodeToString(MediaVariant.serializer(), variant)))
        assertEquals(event, compactJson.decodeFromString(MediaEventPayload.serializer(), compactJson.encodeToString(MediaEventPayload.serializer(), event)))
        listOf(
            PresignRequest(contentType = "image/png", sizeBytes = 1, checksumSha256 = "a".repeat(64)),
            PresignRequest("shoe.png", "image/png", 1, "a".repeat(64)),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(PresignRequest.serializer(), compactJson.encodeToString(PresignRequest.serializer(), value)))
        }
    }
}
