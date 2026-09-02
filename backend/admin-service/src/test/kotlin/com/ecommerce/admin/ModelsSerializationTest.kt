package com.ecommerce.admin

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `bulk job and response preserve operational fields`() {
        val request = BulkJobRequest(listOf("user-1", "user-2"), "{\"active\":true}")
        val response = AdminJobResponse("job-1", "USER_EXPORT", "COMPLETED", 2, 2, 0, 1, null, "2026-08-20T00:00:00Z", "2026-08-20T00:00:01Z")

        assertEquals(request, json.decodeFromString<BulkJobRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<AdminJobResponse>(json.encodeToString(response)))
        val compactJson = Json { encodeDefaults = false; explicitNulls = false }
        val defaults = BulkJobRequest(listOf("user-1"))
        val custom = defaults.copy(payloadJson = "{\"active\":true}")
        assertEquals(defaults, compactJson.decodeFromString(BulkJobRequest.serializer(), compactJson.encodeToString(BulkJobRequest.serializer(), defaults)))
        assertEquals(custom, compactJson.decodeFromString(BulkJobRequest.serializer(), compactJson.encodeToString(BulkJobRequest.serializer(), custom)))
    }
}
