package com.ecommerce.media

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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
import com.ecommerce.platform.error.ErrorCode

class MediaRepositoryBehaviorTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `repository persists media lifecycle variants outbox and ownership`() {
        val database = FakeMediaDatabase()
        val repository = MediaRepository(database.dataSource())

        val created = repository.create(
            MediaInput("owner-1", "media/original.jpg", "bucket", "a".repeat(300), "image/jpeg", 10, "checksum"),
            "corr-create",
        )
        assertEquals(MediaStatus.PENDING_UPLOAD, created.status)
        assertEquals("a".repeat(255), created.originalFilename)
        assertEquals(1, database.jobs)
        assertEquals(1, database.outbox.size)
        assertEquals(created, repository.find(created.id))

        assertEquals(1, repository.markProcessing(created.id))
        val ready = repository.markReady(created.id, 640, null, "corr-ready")
        assertEquals(MediaStatus.READY, ready.status)
        assertEquals(640, ready.width)
        assertNull(ready.height)

        repository.addVariant(created.id, "THUMBNAIL", "media/thumb.jpg", "image/jpeg", 4, 120, 80)
        val withVariant = repository.find(created.id)!!
        assertEquals("media/thumb.jpg", withVariant.variants.single().objectKey)
        assertEquals(120, withVariant.variants.single().width)

        val failed = repository.markFailed(created.id, "processor failed", "corr-failed")
        assertEquals(MediaStatus.FAILED, failed.status)
        assertEquals(3, repository.unpublished(20).size)
        repository.markPublished(database.outbox.map { it.id }, now)
        assertEquals(emptyList(), repository.unpublished(20))

        val deleted = repository.delete(created.id, "owner-1", "corr-delete")
        assertEquals(MediaStatus.DELETED, deleted.status)
    }

    @Test
    fun `repository rejects missing and foreign media owners`() {
        val database = FakeMediaDatabase().also { it.asset = asset(ownerId = "owner-1") }
        val repository = MediaRepository(database.dataSource())

        val forbidden = assertFailsWith<com.ecommerce.platform.error.ApiException> {
            repository.delete("media-1", "owner-2", "corr")
        }
        assertEquals(ErrorCode.FORBIDDEN, forbidden.errorCode)

        val missing = assertFailsWith<com.ecommerce.platform.error.ApiException> {
            MediaRepository(FakeMediaDatabase().dataSource()).delete("missing", "owner-1", "corr")
        }
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode)
        assertNull(MediaRepository(FakeMediaDatabase().dataSource()).find("missing"))
    }

    private fun asset(ownerId: String = "owner-1") = MediaAsset(
        "media-1", ownerId, "media/original.jpg", "bucket", "original.jpg", "image/jpeg", 10, "checksum",
        null, null, MediaStatus.PENDING_UPLOAD, now.toString(), now.toString(), emptyList(),
    )

    private class FakeMediaDatabase {
        private val now = Instant.parse("2026-08-20T00:00:00Z")
        var asset: MediaAsset? = null
        val variants = mutableListOf<MediaVariant>()
        val outbox = mutableListOf<OutboxRow>()
        var jobs = 0

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "createArrayOf", "setAutoCommit", "commit", "rollback", "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(raw: String): PreparedStatement {
            val sql = raw.replace(Regex("\\s+"), " ").trim().uppercase()
            val parameters = mutableMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when {
                    method.name == "setNull" && args?.firstOrNull() is Int -> { parameters[args[0] as Int] = null; null }
                    method.name.startsWith("set") && args?.firstOrNull() is Int -> { parameters[args[0] as Int] = args.getOrNull(1); null }
                    method.name == "executeQuery" -> resultSet(query(sql, parameters))
                    method.name == "executeUpdate" -> update(sql, parameters)
                    method.name == "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun query(sql: String, parameters: Map<Int, Any?>): List<Map<String, Any?>> = when {
            sql.startsWith("SELECT * FROM MEDIA_ASSETS") -> asset?.takeIf { it.id == parameters[1] }?.let { listOf(assetRow(it)) } ?: emptyList()
            sql.startsWith("SELECT * FROM MEDIA_VARIANTS") -> variants.filter { it.mediaId == parameters[1] }.map(::variantRow)
            sql.startsWith("SELECT ID,AGGREGATE_TYPE") -> outbox.filter { !it.published }.map { mapOf("id" to it.id, "aggregate_type" to "Media", "aggregate_id" to it.aggregateId, "event_type" to it.eventType, "schema_version" to 1, "occurred_at" to Timestamp.from(now), "correlation_id" to it.correlationId, "payload_json" to "{}") }
            else -> emptyList()
        }

        private fun update(sql: String, p: Map<Int, Any?>): Int = when {
            sql.startsWith("INSERT INTO MEDIA_ASSETS") -> { asset = MediaAsset(p[1].toString(), p[2].toString(), p[3].toString(), p[4].toString(), p[5] as String?, p[6].toString(), (p[7] as Number).toLong(), p[8].toString(), null, null, MediaStatus.PENDING_UPLOAD, now.toString(), now.toString()); 1 }
            sql.startsWith("INSERT INTO MEDIA_PROCESSING_JOBS") -> { jobs++; 1 }
            sql.startsWith("INSERT INTO MEDIA_VARIANTS") -> { variants.removeIf { it.mediaId == p[2].toString() && it.variantType == p[3].toString() }; variants += MediaVariant(p[1].toString(), p[2].toString(), p[3].toString(), p[4].toString(), p[5].toString(), (p[6] as Number).toLong(), p[7] as Int?, p[8] as Int?); 1 }
            sql.startsWith("INSERT INTO MEDIA_OUTBOX_EVENTS") -> { outbox += OutboxRow(p[1].toString(), p[3].toString(), p[4].toString(), p[6].toString()); 1 }
            sql.startsWith("UPDATE MEDIA_ASSETS SET STATUS=?") -> { asset = asset?.copy(status = MediaStatus.valueOf(p[1].toString()), updatedAt = now.toString()); 1 }
            sql.startsWith("UPDATE MEDIA_ASSETS SET STATUS='READY'") -> { asset = asset?.copy(status = MediaStatus.READY, width = p[1] as Int?, height = p[2] as Int?, updatedAt = now.toString()); 1 }
            sql.startsWith("UPDATE MEDIA_ASSETS SET STATUS='FAILED'") -> { asset = asset?.copy(status = MediaStatus.FAILED, updatedAt = now.toString()); 1 }
            sql.startsWith("UPDATE MEDIA_ASSETS SET STATUS='DELETED'") -> { asset = asset?.copy(status = MediaStatus.DELETED, updatedAt = now.toString()); 1 }
            sql.startsWith("UPDATE MEDIA_OUTBOX_EVENTS") -> { outbox.forEach { it.published = true }; 1 }
            sql.startsWith("UPDATE MEDIA_PROCESSING_JOBS") -> 1
            else -> 1
        }

        private fun assetRow(value: MediaAsset) = mapOf<String, Any?>(
            "id" to value.id, "owner_id" to value.ownerId, "object_key" to value.objectKey, "bucket" to value.bucket,
            "original_filename" to value.originalFilename, "content_type" to value.contentType, "size_bytes" to value.sizeBytes,
            "checksum_sha256" to value.checksumSha256, "width" to value.width, "height" to value.height,
            "status" to value.status.name, "created_at" to Timestamp.from(now), "updated_at" to Timestamp.from(now),
        )

        private fun variantRow(value: MediaVariant) = mapOf<String, Any?>(
            "id" to value.id, "media_id" to value.mediaId, "variant_type" to value.variantType, "object_key" to value.objectKey,
            "content_type" to value.contentType, "size_bytes" to value.sizeBytes, "width" to value.width, "height" to value.height,
        )

        private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
            var index = -1
            var last: Any? = null
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString", "getLong", "getInt", "getTimestamp" -> {
                        val key = args?.firstOrNull()
                        last = rows[index][key.toString()] ?: (key as? Int)?.let { rows[index].values.elementAtOrNull(it - 1) }
                        when (method.name) {
                            "getString" -> last?.toString()
                            "getLong" -> (last as? Number)?.toLong() ?: 0L
                            "getInt" -> (last as? Number)?.toInt() ?: 0
                            else -> last
                        }
                    }
                    "wasNull" -> last == null
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        data class OutboxRow(val id: String, val aggregateId: String, val eventType: String, val correlationId: String, var published: Boolean = false)
    }
}

private fun defaultValue(type: Class<*>): Any? = when {
    !type.isPrimitive -> null
    type == Boolean::class.javaPrimitiveType -> false
    type == Int::class.javaPrimitiveType -> 0
    type == Long::class.javaPrimitiveType -> 0L
    type == Double::class.javaPrimitiveType -> 0.0
    type == Float::class.javaPrimitiveType -> 0f
    type == Short::class.javaPrimitiveType -> 0.toShort()
    type == Byte::class.javaPrimitiveType -> 0.toByte()
    type == Char::class.javaPrimitiveType -> '\u0000'
    else -> null
}
