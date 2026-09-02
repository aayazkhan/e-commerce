package com.ecommerce.flags

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Array
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class FlagRepositoryBehaviorTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `evaluation returns safe fallbacks for missing environment disabled and unmatched targeting`() {
        val missing = FlagRepository(dataSource(null), json).evaluate(EvaluationRequest("missing", fallbackValue = "fallback"))
        assertEquals(EvaluationResponse("missing", "fallback", false, 0, "safe-default"), missing)

        val environment = FlagRepository(dataSource(flag(environment = "staging")), json).evaluate(EvaluationRequest("checkout", environment = "production", fallbackValue = "fallback"))
        assertEquals("environment-default", environment.source)
        assertEquals("false", environment.value)

        val disabled = FlagRepository(dataSource(flag(enabled = false)), json).evaluate(EvaluationRequest("checkout"))
        assertEquals("disabled", disabled.source)

        val unmatched = FlagRepository(dataSource(flag(rulesJson = "{\"country\":\"US\"}")), json).evaluate(EvaluationRequest("checkout", country = "IN"))
        assertEquals("target-default", unmatched.source)
    }

    @Test
    fun `evaluation covers targeting dimensions versions and deterministic rollout boundaries`() {
        val rules = """{"platform":"ANDROID","country":"IN","sellerId":"seller-1","userIds":"user-1","appVersion":"2.0","minimumVersion":"2.0"}"""
        val included = FlagRepository(dataSource(flag(rolloutBps = 10_000, rulesJson = rules)), json).evaluate(
            EvaluationRequest("checkout", userId = "user-1", country = "IN", platform = "ANDROID", appVersion = "2.0", sellerId = "seller-1"),
        )
        assertEquals("rollout", included.source)
        assertTrue(included.enabled)
        assertEquals("true", included.value)

        val excluded = FlagRepository(dataSource(flag(rolloutBps = 0, rulesJson = rules)), json).evaluate(
            EvaluationRequest("checkout", userId = "user-1", country = "IN", platform = "ANDROID", appVersion = "2.0", sellerId = "seller-1"),
        )
        assertEquals("rollout-excluded", excluded.source)
        assertEquals("false", excluded.value)

        val oldVersion = FlagRepository(dataSource(flag(rolloutBps = 10_000, rulesJson = rules)), json).evaluate(
            EvaluationRequest("checkout", userId = "user-1", country = "IN", platform = "ANDROID", appVersion = "1.9", sellerId = "seller-1"),
        )
        assertEquals("target-default", oldVersion.source)

        val sellerIdentity = FlagRepository(dataSource(flag(rolloutBps = 10_000, rulesJson = "{\"sellerId\":\"seller-1\"}")), json).evaluate(
            EvaluationRequest("checkout", sellerId = "seller-1"),
        )
        assertEquals("rollout", sellerIdentity.source)
        val anonymous = FlagRepository(dataSource(flag(rolloutBps = 10_000)), json).evaluate(EvaluationRequest("checkout"))
        assertEquals("rollout", anonymous.source)
        val malformed = FlagRepository(dataSource(flag(rolloutBps = 10_000, rulesJson = "not-json")), json).evaluate(EvaluationRequest("checkout"))
        assertEquals("target-default", malformed.source)

        val missingDimension = FlagRepository(dataSource(flag(rolloutBps = 10_000, rulesJson = "{\"platform\":\"ANDROID\"}")), json).evaluate(EvaluationRequest("checkout"))
        assertEquals("target-default", missingDimension.source)

        val whitespaceSeparated = FlagRepository(
            dataSource(flag(rolloutBps = 10_000, rulesJson = "{\"platform\":\"ANDROID, , IOS\"}")),
            json,
        ).evaluate(EvaluationRequest("checkout", platform = "ANDROID"))
        assertEquals("rollout", whitespaceSeparated.source)
    }

    @Test
    fun `evaluation rejects each targeting mismatch and covers version ordering outcomes`() {
        val cases = listOf(
            "platform" to EvaluationRequest("checkout", platform = "IOS"),
            "country" to EvaluationRequest("checkout", country = "US"),
            "sellerId" to EvaluationRequest("checkout", sellerId = "seller-2"),
            "userIds" to EvaluationRequest("checkout", userId = "user-2"),
            "appVersion" to EvaluationRequest("checkout", appVersion = "1.9"),
        )
        cases.forEach { (dimension, request) ->
            val expected = when (dimension) {
                "platform" -> "ANDROID"
                "country" -> "IN"
                "sellerId" -> "seller-1"
                "userIds" -> "user-1"
                else -> "2.0"
            }
            val response = FlagRepository(dataSource(flag(rulesJson = "{\"$dimension\":\"$expected\"}")), json).evaluate(request)
            assertEquals("target-default", response.source, dimension)
        }

        val minimumVersion = "{\"minimumVersion\":\"2.0\"}"
        assertEquals(
            "target-default",
            FlagRepository(dataSource(flag(rulesJson = minimumVersion)), json).evaluate(EvaluationRequest("checkout")).source,
        )
        assertEquals(
            "rollout",
            FlagRepository(dataSource(flag(rulesJson = minimumVersion)), json).evaluate(EvaluationRequest("checkout", appVersion = "3.0")).source,
        )
        assertEquals(
            "rollout",
            FlagRepository(dataSource(flag(rulesJson = minimumVersion)), json).evaluate(EvaluationRequest("checkout", appVersion = "2.beta")).source,
        )
    }

    @Test
    fun `evaluation compares uneven and malformed version segments deterministically`() {
        val rules = "{\"minimumVersion\":\"2.0.1\"}"
        val repository = FlagRepository(dataSource(flag(rulesJson = rules)), json)

        assertEquals("rollout", repository.evaluate(EvaluationRequest("checkout", appVersion = "2.0.1")).source)
        assertEquals("target-default", repository.evaluate(EvaluationRequest("checkout", appVersion = "2.0")).source)
        assertEquals("rollout", repository.evaluate(EvaluationRequest("checkout", appVersion = "2.0.1.4")).source)
        assertEquals("target-default", repository.evaluate(EvaluationRequest("checkout", appVersion = "2-alpha")).source)

        val equalWithPadding = FlagRepository(dataSource(flag(rulesJson = "{\"minimumVersion\":\"2.0\"}")), json)
        assertEquals("rollout", equalWithPadding.evaluate(EvaluationRequest("checkout", appVersion = "2.0.0")).source)

        val malformedMinimum = FlagRepository(dataSource(flag(rulesJson = "{\"minimumVersion\":\"2.beta\"}")), json)
        assertEquals("rollout", malformedMinimum.evaluate(EvaluationRequest("checkout", appVersion = "2.1")).source)
    }

    @Test
    fun `list get delete and outbox boundaries map persisted flags and errors`() {
        val database = FlagDatabase(flag())
        val repository = FlagRepository(database.dataSource(), json)
        assertEquals("checkout.v2", repository.get("checkout.v2")!!.key)
        assertEquals(1, repository.list().size)
        repository.delete("admin", "checkout.v2", "corr")
        assertTrue(repository.unpublished(10).isEmpty())
        repository.markPublished(emptyList(), Instant.now())

        val missing = FlagRepository(FlagDatabase(null).dataSource(), json)
        assertNull(missing.get("missing"))
        assertEquals(404, assertFailsWith<ApiException> { missing.delete("admin", "missing", "corr") }.statusCode)
    }

    @Test
    fun `flag persistence covers create update optimistic locking and validation`() {
        val database = FlagDatabase(null)
        val repository = FlagRepository(database.dataSource(), json)
        val request = FeatureFlagRequest("checkout.v3", FlagValueType.BOOLEAN, "true", "false", rolloutBps = 5000, rulesJson = "{}")
        val created = repository.save("admin", request, "corr-create")
        assertEquals("checkout.v3", created.key)
        assertEquals(1L, created.version)

        val pending = repository.unpublished(10)
        assertEquals(1, pending.size)
        repository.markPublished(listOf(pending.single().id), Instant.parse("2026-08-21T00:00:00Z"))
        assertTrue(repository.unpublished(10).isEmpty())
        repository.markPublished(emptyList(), Instant.parse("2026-08-21T00:00:00Z"))

        val updated = repository.save("admin-2", request.copy(defaultValue = "false", expectedVersion = 1), "corr-update")
        assertEquals("false", updated.defaultValue)
        assertEquals(2L, updated.version)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.save("admin-3", request.copy(expectedVersion = 1), "corr-conflict")
        }.errorCode)

        val updateConflict = FlagRepository(FlagDatabase(flag(), forceUpdateConflict = true).dataSource(), json)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            updateConflict.save("admin", request.copy(key = "checkout.v2", expectedVersion = 4), "corr-conflict")
        }.errorCode)
    }

    @Test
    fun `flag persistence rejects malformed keys rollout and JSON values`() {
        val repository = FlagRepository(FlagDatabase(null).dataSource(), json)
        val base = FeatureFlagRequest("valid.key", FlagValueType.BOOLEAN, "true", "false")
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.save("admin", base.copy(key = "Invalid Key"), "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.save("admin", base.copy(rolloutBps = 10_001), "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.save("admin", base.copy(defaultValue = "{"), "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.save("admin", base.copy(failSafeValue = "{"), "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.save("admin", base.copy(rulesJson = "{"), "corr")
        }.errorCode)
    }

    private fun flag(
        enabled: Boolean = true,
        environment: String = "production",
        rolloutBps: Int = 10_000,
        rulesJson: String = "{}",
    ) = FeatureFlagResponse("checkout.v2", FlagValueType.BOOLEAN, "true", "false", enabled, environment, rolloutBps, rulesJson, 4, "admin", "2026-08-20T10:00:00Z")

    private fun dataSource(response: FeatureFlagResponse?): DataSource = FlagDatabase(response).dataSource()
}

private class FlagDatabase(private var response: FeatureFlagResponse?, private val forceUpdateConflict: Boolean = false) {
    private var outboxPublished = false
    fun dataSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ -> if (method.name == "getConnection") connection() else defaultValue(method.returnType) }) as DataSource

    private fun connection(): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args -> when (method.name) {
        "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
        "createArrayOf" -> sqlArray(args?.getOrNull(1))
        "setAutoCommit", "commit", "rollback", "close" -> null
        else -> defaultValue(method.returnType)
    } }) as Connection

    private fun statement(raw: String): PreparedStatement {
        val sql = raw.replace(Regex("\\s+"), " ").trim().uppercase()
        val parameters = mutableMapOf<Int, Any?>()
        return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args -> when {
            method.name.startsWith("set") && args?.firstOrNull() is Int -> { parameters[args[0] as Int] = args.getOrNull(1); null }
            method.name == "executeQuery" -> resultSet(when {
                sql.startsWith("SELECT ID,AGGREGATE_TYPE") && response != null && !outboxPublished -> listOf(mapOf("id" to "flag-event-1", "aggregate_type" to "FeatureFlag", "aggregate_id" to response!!.key, "event_type" to "FeatureFlagChanged", "schema_version" to 1, "occurred_at" to Timestamp.from(Instant.parse("2026-08-21T00:00:00Z")), "correlation_id" to "corr", "payload_json" to "{}"))
                sql.contains("FROM FEATURE_FLAGS") && response != null -> listOf(row(response!!))
                else -> emptyList()
            })
            method.name == "executeUpdate" -> when {
                sql.startsWith("INSERT INTO FEATURE_FLAGS") -> { response = FeatureFlagResponse(parameters[1].toString(), FlagValueType.valueOf(parameters[2].toString()), parameters[3].toString(), parameters[4].toString(), parameters[5] as Boolean, parameters[6].toString(), (parameters[7] as Number).toInt(), parameters[8].toString(), (parameters[9] as Number).toLong(), parameters[10].toString(), "2026-08-21T00:00:00Z"); 1 }
                sql.startsWith("UPDATE FEATURE_FLAGS") && forceUpdateConflict -> 0
                sql.startsWith("UPDATE FEATURE_FLAGS") -> { response = FeatureFlagResponse(parameters[10].toString(), FlagValueType.valueOf(parameters[1].toString()), parameters[2].toString(), parameters[3].toString(), parameters[4] as Boolean, parameters[5].toString(), (parameters[6] as Number).toInt(), parameters[7].toString(), (parameters[8] as Number).toLong(), parameters[9].toString(), "2026-08-21T00:00:00Z"); 1 }
                sql.startsWith("DELETE FROM FEATURE_FLAGS") -> { response = null; 1 }
                sql.startsWith("UPDATE FEATURE_FLAG_OUTBOX_EVENTS") -> { outboxPublished = true; 1 }
                else -> 1
            }
            method.name == "close" -> null
            else -> defaultValue(method.returnType)
        } }) as PreparedStatement
    }

    private fun row(flag: FeatureFlagResponse) = mapOf<String, Any?>(
        "key" to flag.key, "value_type" to flag.valueType.name, "default_value" to flag.defaultValue, "fail_safe_value" to flag.failSafeValue,
        "enabled" to flag.enabled, "environment" to flag.environment, "rollout_bps" to flag.rolloutBps, "rules_json" to flag.rulesJson,
        "version" to flag.version, "updated_by" to flag.updatedBy, "updated_at" to Timestamp.from(Instant.parse(flag.updatedAt)),
    )

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        var wasNull = false
        fun value(key: Any): Any? { val row = rows.getOrNull(index); val k = key.toString(); val actual = k.toIntOrNull()?.let { row?.keys?.elementAtOrNull(it - 1) } ?: k; val value = row?.get(actual); wasNull = value == null; return value }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args -> when (method.name) {
            "next" -> if (index + 1 < rows.size) { index++; true } else false
            "getString" -> value(args?.firstOrNull() ?: "")?.toString()
            "getInt" -> (value(args?.firstOrNull() ?: "") as? Number)?.toInt() ?: 0
            "getLong" -> (value(args?.firstOrNull() ?: "") as? Number)?.toLong() ?: 0L
            "getBoolean" -> value(args?.firstOrNull() ?: "") as? Boolean ?: false
            "getTimestamp" -> value(args?.firstOrNull() ?: "") as? Timestamp
            "wasNull" -> wasNull
            "close" -> null
            else -> defaultValue(method.returnType)
        } }) as ResultSet
    }

    private fun sqlArray(values: Any?): Array = Proxy.newProxyInstance(Array::class.java.classLoader, arrayOf(Array::class.java), InvocationHandler { _, method, _ -> if (method.name == "getArray") values else defaultValue(method.returnType) }) as Array
    private fun defaultValue(type: Class<*>): Any? = when (type) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
}
