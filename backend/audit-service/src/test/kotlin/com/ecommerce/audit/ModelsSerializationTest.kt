package com.ecommerce.audit

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `audit request and page preserve optional actor context`() {
        val request = AuditRequest(actorId = "admin-1", action = "REFUND_APPROVE", resourceType = "refund", resourceId = "rfd-1", beforeJson = "{}", afterJson = "{\"status\":\"APPROVED\"}", requestId = "req-1", traceId = "trace-1")
        val item = AuditResponse("audit-1", "admin-1", "ADMIN", request.action, request.resourceType, request.resourceId, null, request.beforeJson, request.afterJson, null, request.requestId, request.traceId, "2026-08-20T00:00:00Z")
        val page = AuditPage(listOf(item), null)

        assertEquals(request, json.decodeFromString<AuditRequest>(json.encodeToString(request)))
        assertEquals(page, json.decodeFromString<AuditPage>(json.encodeToString(page)))
    }

    @Test
    fun `audit serializers cover default and complete nullable context`() {
        val minimal = AuditRequest(action = "LOGIN", resourceType = "user", resourceId = "user-1")
        val complete = AuditRequest(
            actorId = "user-1",
            actorType = "USER",
            action = "PROFILE_UPDATE",
            resourceType = "user",
            resourceId = "user-1",
            sellerId = "seller-1",
            beforeJson = "{\"name\":\"old\"}",
            afterJson = "{\"name\":\"new\"}",
            reason = "customer request",
            requestId = "req-2",
            traceId = "trace-2",
            ip = "192.0.2.10",
            userAgent = "test-agent"
        )
        assertEquals(minimal, json.decodeFromString<AuditRequest>(json.encodeToString(minimal)))
        assertEquals(complete, json.decodeFromString<AuditRequest>(json.encodeToString(complete)))
        assertEquals(minimal, json.decodeFromString<AuditRequest>("{\"action\":\"LOGIN\",\"resourceType\":\"user\",\"resourceId\":\"user-1\"}"))

        val completeResponse = AuditResponse(
            "audit-2", "user-1", "USER", "PROFILE_UPDATE", "user", "user-1", "seller-1",
            "{}", "{}", "customer request", "req-2", "trace-2", "2026-08-20T00:00:00Z"
        )
        assertEquals(completeResponse, json.decodeFromString<AuditResponse>(json.encodeToString(completeResponse)))
        assertEquals(AuditPage(emptyList(), "next"), json.decodeFromString<AuditPage>(json.encodeToString(AuditPage(emptyList(), "next"))))
    }

    @Test
    fun `compact audit serialization preserves masked optional context and empty pages`() {
        val request = AuditRequest(action = "LOGIN", resourceType = "user", resourceId = "user-1")
        val response = AuditResponse("audit-1", null, "SYSTEM", "LOGIN", "user", "user-1", null, null, null, null, null, null, "2026-08-20T00:00:00Z")
        val page = AuditPage(emptyList(), null)
        assertEquals(request, compactJson.decodeFromString(AuditRequest.serializer(), compactJson.encodeToString(AuditRequest.serializer(), request)))
        assertEquals(response, compactJson.decodeFromString(AuditResponse.serializer(), compactJson.encodeToString(AuditResponse.serializer(), response)))
        assertEquals(page, compactJson.decodeFromString(AuditPage.serializer(), compactJson.encodeToString(AuditPage.serializer(), page)))

        listOf(
            request.copy(actorId = "user-1"),
            request.copy(actorType = "USER"),
            request.copy(sellerId = "seller-1"),
            request.copy(beforeJson = "{}"),
            request.copy(afterJson = "{}"),
            request.copy(reason = "customer request"),
            request.copy(requestId = "req-1"),
            request.copy(traceId = "trace-1"),
            request.copy(ip = "192.0.2.1"),
            request.copy(userAgent = "test-agent"),
        ).forEach { variant ->
            assertEquals(
                variant,
                compactJson.decodeFromString(AuditRequest.serializer(), compactJson.encodeToString(AuditRequest.serializer(), variant)),
            )
        }
    }
}
