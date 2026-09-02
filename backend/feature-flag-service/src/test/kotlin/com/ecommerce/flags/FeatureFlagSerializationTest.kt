package com.ecommerce.flags

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureFlagSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `flag request and response preserve type rollout rules and version`() {
        FlagValueType.entries.forEach { type ->
            val request = FeatureFlagRequest("checkout.v2", type, "false", "false", true, "staging", 2500, "{\"country\":\"IN\"}", 4)
            val response = FeatureFlagResponse("checkout.v2", type, "false", "false", true, "staging", 2500, "{}", 5, "admin-1", "2026-08-20T10:00:00Z")
            assertEquals(request, json.decodeFromString<FeatureFlagRequest>(json.encodeToString(request)))
            assertEquals(response, json.decodeFromString<FeatureFlagResponse>(json.encodeToString(response)))
        }
    }

    @Test
    fun `evaluation request and response preserve optional targeting fields`() {
        val request = EvaluationRequest("search.v2", "user-1", "IN", "ANDROID", "9.1.0", "seller-1", "production", "false")
        val response = EvaluationResponse("search.v2", "true", true, 8, "rollout")
        assertEquals(request, json.decodeFromString<EvaluationRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<EvaluationResponse>(json.encodeToString(response)))
    }

    @Test
    fun `flag serializers cover defaults and absent targeting`() {
        val defaults = FeatureFlagRequest("checkout.v2", FlagValueType.BOOLEAN, "false", "false")
        assertEquals(defaults, json.decodeFromString<FeatureFlagRequest>(json.encodeToString(defaults)))
        assertEquals(defaults, json.decodeFromString<FeatureFlagRequest>("{\"key\":\"checkout.v2\",\"valueType\":\"BOOLEAN\",\"defaultValue\":\"false\",\"failSafeValue\":\"false\"}"))
        val noExpectedVersion = defaults.copy(enabled = false, environment = "test", rolloutBps = 0, rulesJson = "{}", expectedVersion = null)
        assertEquals(noExpectedVersion, json.decodeFromString<FeatureFlagRequest>(json.encodeToString(noExpectedVersion)))

        val withoutTarget = EvaluationRequest("checkout.v2")
        assertEquals(withoutTarget, json.decodeFromString<EvaluationRequest>(json.encodeToString(withoutTarget)))
        assertEquals(withoutTarget, json.decodeFromString<EvaluationRequest>("{\"key\":\"checkout.v2\"}"))
        val sellerOnly = EvaluationRequest("seller.v2", sellerId = "seller-1", fallbackValue = "true")
        assertEquals(sellerOnly, json.decodeFromString<EvaluationRequest>(json.encodeToString(sellerOnly)))
    }

    @Test
    fun `compact flag serialization preserves rollout defaults and nullable targeting`() {
        val request = FeatureFlagRequest("checkout.v2", FlagValueType.JSON, "{}", "false")
        val evaluation = EvaluationRequest("checkout.v2")
        val response = EvaluationResponse("checkout.v2", "false", false, 1, "safe-default")

        assertEquals(request, compactJson.decodeFromString(FeatureFlagRequest.serializer(), compactJson.encodeToString(FeatureFlagRequest.serializer(), request)))
        assertEquals(evaluation, compactJson.decodeFromString(EvaluationRequest.serializer(), compactJson.encodeToString(EvaluationRequest.serializer(), evaluation)))
        assertEquals(response, compactJson.decodeFromString(EvaluationResponse.serializer(), compactJson.encodeToString(EvaluationResponse.serializer(), response)))
        val defaults = FeatureFlagRequest("checkout.v2", FlagValueType.BOOLEAN, "false", "false")
        listOf(
            defaults.copy(enabled = false),
            defaults.copy(environment = "staging"),
            defaults.copy(rolloutBps = 2_500),
            defaults.copy(rulesJson = "{\"country\":\"IN\"}"),
            defaults.copy(expectedVersion = 3),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(FeatureFlagRequest.serializer(), compactJson.encodeToString(FeatureFlagRequest.serializer(), value)))
        }
        listOf(
            evaluation,
            evaluation.copy(userId = "user-1"),
            evaluation.copy(country = "IN"),
            evaluation.copy(platform = "ANDROID"),
            evaluation.copy(appVersion = "9.1.0"),
            evaluation.copy(sellerId = "seller-1"),
            evaluation.copy(environment = "staging"),
            evaluation.copy(fallbackValue = "true"),
        ).forEach { value ->
            assertEquals(value, compactJson.decodeFromString(EvaluationRequest.serializer(), compactJson.encodeToString(EvaluationRequest.serializer(), value)))
        }
    }
}
