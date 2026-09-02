package com.ecommerce.cms

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Array
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CmsRepositoryLifecycleTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `create list get public outbox and mark published cover persistence boundaries`() {
        val database = FakeCmsDatabase()
        val repository = CmsRepository(database.dataSource(), Json.Default)

        val created = repository.create("admin-1", request(), "corr-1")
        assertEquals(CmsStatus.DRAFT, created.status)
        assertEquals(1, created.currentVersion)
        assertEquals("Page title", created.title)
        assertEquals(created, repository.get(created.id))
        assertEquals(1, repository.list(0).size)
        database.includeMissingListEntry = true
        assertEquals(1, repository.list(10).size)
        assertNull(repository.public("page-slug"))

        val events = repository.unpublished(10)
        assertEquals(1, events.size)
        assertEquals("CmsCreated", events.single().eventType)
        repository.markPublished(emptyList(), now)
        repository.markPublished(listOf(events.single().id), now)
        assertTrue(database.publishedOutbox)
    }

    @Test
    fun `valid lifecycle transitions and invalid transitions are enforced`() {
        val database = FakeCmsDatabase.seed(status = CmsStatus.DRAFT)
        val repository = CmsRepository(database.dataSource(), Json.Default)

        assertEquals(CmsStatus.IN_REVIEW, repository.submit("page-1", "editor", "c1").status)
        assertEquals(CmsStatus.APPROVED, repository.approve("page-1", "reviewer", "c2").status)
        assertEquals(CmsStatus.PUBLISHED, repository.publish("page-1", "publisher", CmsPublishRequest(), "c3").status)
        assertEquals(CmsStatus.UNPUBLISHED, repository.unpublish("page-1", "publisher", "c4").status)
        assertEquals(CmsStatus.ARCHIVED, repository.archive("page-1", "admin", "c5").status)

        val publicPage = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.PUBLISHED).dataSource(), Json.Default).public("page-slug")
        assertEquals(CmsStatus.PUBLISHED, publicPage?.status)

        val error = assertFailsWith<ApiException> { repository.submit("page-1", "editor", "c6") }
        assertEquals(ErrorCode.CONFLICT, error.errorCode)

        val draft = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.DRAFT).dataSource(), Json.Default)
        assertEquals(CmsStatus.ARCHIVED, draft.archive("page-1", "admin", "c7").status)

        val scheduled = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.SCHEDULED).dataSource(), Json.Default)
        assertEquals(CmsStatus.UNPUBLISHED, scheduled.unpublish("page-1", "publisher", "c8").status)
        assertEquals(
            CmsStatus.ARCHIVED,
            CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.SCHEDULED).dataSource(), Json.Default)
                .archive("page-1", "admin", "c8-archive").status,
        )

        val unpublished = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.UNPUBLISHED).dataSource(), Json.Default)
        assertEquals(CmsStatus.IN_REVIEW, unpublished.submit("page-1", "editor", "c9").status)
        assertEquals(
            CmsStatus.ARCHIVED,
            CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.UNPUBLISHED).dataSource(), Json.Default)
                .archive("page-1", "admin", "c9-archive").status,
        )

        assertEquals(
            CmsStatus.ARCHIVED,
            CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.PUBLISHED).dataSource(), Json.Default)
                .archive("page-1", "admin", "c9-published-archive").status,
        )

        val archived = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.ARCHIVED).dataSource(), Json.Default)
        val terminalError = assertFailsWith<ApiException> { archived.archive("page-1", "admin", "c10") }
        assertEquals(ErrorCode.CONFLICT, terminalError.errorCode)

        val inReview = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.IN_REVIEW).dataSource(), Json.Default)
        val invalidReviewTransition = assertFailsWith<ApiException> { inReview.submit("page-1", "editor", "c11") }
        assertEquals(ErrorCode.CONFLICT, invalidReviewTransition.errorCode)

        val approved = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.APPROVED).dataSource(), Json.Default)
        val invalidApprovedTransition = assertFailsWith<ApiException> { approved.unpublish("page-1", "publisher", "c12") }
        assertEquals(ErrorCode.CONFLICT, invalidApprovedTransition.errorCode)
    }

    @Test
    fun `update supports optimistic locking and maps missing and stale writes`() {
        val successDatabase = FakeCmsDatabase.seed(status = CmsStatus.DRAFT)
        val success = CmsRepository(successDatabase.dataSource(), Json.Default).update(
            "page-1", "editor", request(title = "Updated", expectedVersion = 1), "corr-update"
        )
        assertEquals("Updated", success.title)
        assertEquals(2, success.currentVersion)

        val withoutClientVersion = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.DRAFT).dataSource(), Json.Default).update(
            "page-1", "editor", request(title = "Updated without client version"), "corr-update-no-version",
        )
        assertEquals("Updated without client version", withoutClientVersion.title)

        val stale = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.DRAFT).dataSource(), Json.Default)
        val staleError = assertFailsWith<ApiException> {
            stale.update("page-1", "editor", request(expectedVersion = 99), "corr-stale")
        }
        assertEquals(ErrorCode.CONFLICT, staleError.errorCode)

        val databaseConflict = FakeCmsDatabase.seed(status = CmsStatus.DRAFT).also { it.forceUpdateConflict = true }
        val conflictError = assertFailsWith<ApiException> {
            CmsRepository(databaseConflict.dataSource(), Json.Default).update("page-1", "editor", request(), "corr-db")
        }
        assertEquals(ErrorCode.CONFLICT, conflictError.errorCode)

        val missing = CmsRepository(FakeCmsDatabase().dataSource(), Json.Default)
        val missingError = assertFailsWith<ApiException> { missing.update("missing", "editor", request(), "corr-missing") }
        assertEquals(ErrorCode.NOT_FOUND, missingError.errorCode)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> { missing.publish("missing", "publisher", CmsPublishRequest(), "corr-publish") }.errorCode)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> { missing.rollback("missing", "editor", CmsRollbackRequest(1, 1), "corr-rollback") }.errorCode)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> { missing.submit("missing", "editor", "corr-submit") }.errorCode)
    }

    @Test
    fun `publish validates state version and timestamps and supports future scheduling`() {
        val invalidState = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.DRAFT).dataSource(), Json.Default)
        val stateError = assertFailsWith<ApiException> {
            invalidState.publish("page-1", "publisher", CmsPublishRequest(), "c1")
        }
        assertEquals(ErrorCode.CONFLICT, stateError.errorCode)

        val invalidVersion = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.APPROVED).dataSource(), Json.Default)
        val versionError = assertFailsWith<ApiException> {
            invalidVersion.publish("page-1", "publisher", CmsPublishRequest(version = 99), "c2")
        }
        assertEquals(ErrorCode.CONFLICT, versionError.errorCode)

        val invalidTime = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.APPROVED).dataSource(), Json.Default)
        val timeError = assertFailsWith<ApiException> {
            invalidTime.publish("page-1", "publisher", CmsPublishRequest(publishAt = "tomorrow"), "c3")
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, timeError.errorCode)

        val scheduledDatabase = FakeCmsDatabase.seed(status = CmsStatus.APPROVED)
        val scheduled = CmsRepository(scheduledDatabase.dataSource(), Json.Default).publish(
            "page-1", "publisher", CmsPublishRequest(publishAt = "2099-01-01T00:00:00Z", unpublishAt = "2099-01-01T01:00:00Z", timezone = "Asia/Kolkata"), "c4"
        )
        assertEquals(CmsStatus.SCHEDULED, scheduled.status)
        assertEquals(1, scheduledDatabase.schedules.size)

        val malformedUnpublish = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.APPROVED).dataSource(), Json.Default)
        val unpublishError = assertFailsWith<ApiException> {
            malformedUnpublish.publish("page-1", "publisher", CmsPublishRequest(publishAt = "2099-01-01T00:00:00Z", unpublishAt = "bad"), "c5")
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, unpublishError.errorCode)

        val scheduledWithoutUnpublish = FakeCmsDatabase.seed(status = CmsStatus.APPROVED)
        assertEquals(
            CmsStatus.SCHEDULED,
            CmsRepository(scheduledWithoutUnpublish.dataSource(), Json.Default).publish(
                "page-1", "publisher", CmsPublishRequest(publishAt = "2099-01-01T00:00:00Z"), "c6",
            ).status,
        )

        val pastSchedule = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.APPROVED).dataSource(), Json.Default)
        assertEquals(
            CmsStatus.PUBLISHED,
            pastSchedule.publish("page-1", "publisher", CmsPublishRequest(publishAt = "2020-01-01T00:00:00Z"), "c7").status,
        )
    }

    @Test
    fun `rollback restores a known version and rejects stale or missing versions`() {
        val database = FakeCmsDatabase.seed(status = CmsStatus.DRAFT, currentVersion = 2, version = 3)
        database.versions[2] = "{\"body\":\"v2\"}"
        val restored = CmsRepository(database.dataSource(), Json.Default).rollback(
            "page-1", "editor", CmsRollbackRequest(version = 1, expectedVersion = 3), "corr-rollback"
        )
        assertEquals(3, restored.currentVersion)
        assertEquals("{\"body\":\"v1\"}", restored.contentJson)

        val stale = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.DRAFT, currentVersion = 2, version = 3).dataSource(), Json.Default)
        val staleError = assertFailsWith<ApiException> {
            stale.rollback("page-1", "editor", CmsRollbackRequest(version = 1, expectedVersion = 2), "c1")
        }
        assertEquals(ErrorCode.CONFLICT, staleError.errorCode)

        val missingVersion = CmsRepository(FakeCmsDatabase.seed(status = CmsStatus.DRAFT).dataSource(), Json.Default)
        val missingError = assertFailsWith<ApiException> {
            missingVersion.rollback("page-1", "editor", CmsRollbackRequest(version = 99, expectedVersion = 1), "c2")
        }
        assertEquals(ErrorCode.NOT_FOUND, missingError.errorCode)
    }

    @Test
    fun `publish due handles scheduled publish unpublish completion and missing page`() {
        assertEquals(0, CmsRepository(FakeCmsDatabase().dataSource(), Json.Default).publishDue())
        val database = FakeCmsDatabase.seed(status = CmsStatus.APPROVED)
        database.schedules += FakeSchedule("job-publish", "page-1", 1, "PENDING", now.plusSeconds(10), now.plusSeconds(20))
        val repository = CmsRepository(database.dataSource(), Json.Default)
        assertEquals(1, repository.publishDue())
        assertEquals(CmsStatus.PUBLISHED, repository.get("page-1")!!.status)
        assertEquals("PUBLISHED", database.schedules.single().state)
        assertEquals(1, repository.publishDue())
        assertEquals(CmsStatus.UNPUBLISHED, repository.get("page-1")!!.status)
        assertEquals("COMPLETED", database.schedules.single().state)

        val complete = FakeCmsDatabase.seed(status = CmsStatus.APPROVED)
        complete.schedules += FakeSchedule("job-complete", "page-1", 1, "PENDING", null)
        assertEquals(1, CmsRepository(complete.dataSource(), Json.Default).publishDue())
        assertEquals("COMPLETED", complete.schedules.single().state)

        val missingPage = FakeCmsDatabase.seed(status = CmsStatus.APPROVED)
        missingPage.schedules += FakeSchedule("job-missing", "missing", 1, "PENDING", null)
        assertEquals(0, CmsRepository(missingPage.dataSource(), Json.Default).publishDue())
    }

    @Test
    fun `transaction rolls back when outbox persistence fails`() {
        val database = FakeCmsDatabase().also { it.failOutbox = true }
        val error = assertFailsWith<SQLException> {
            CmsRepository(database.dataSource(), Json.Default).create("admin", request(), "corr")
        }
        assertEquals(1, database.rollbackCount)
        assertEquals(0, database.commitCount)
        assertEquals("23505", error.sqlState)
    }

    private fun request(slug: String = "page-slug", title: String = "Page title", expectedVersion: Long? = null) =
        CmsPageRequest(slug, title, "{\"body\":\"v1\"}", SeoMetadata(title = "SEO"), expectedVersion)

    private class FakeCmsDatabase(
        seed: Boolean = false,
        initialStatus: CmsStatus = CmsStatus.DRAFT,
        initialCurrentVersion: Int = 1,
        initialVersion: Long = 1,
    ) {
        private val clock = Instant.parse("2026-08-20T00:00:00Z")
        var page: FakePage? = if (seed) FakePage("page-1", initialStatus, initialCurrentVersion, initialVersion) else null
        val versions = linkedMapOf(1 to "{\"body\":\"v1\"}")
        val schedules = mutableListOf<FakeSchedule>()
        val outbox = mutableListOf<FakeOutbox>()
        var forceUpdateConflict = false
        var failOutbox = false
        var publishedOutbox = false
        var includeMissingListEntry = false
        var commitCount = 0
        var rollbackCount = 0

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "createArrayOf" -> null
                    "setAutoCommit" -> null
                    "commit" -> { commitCount++; null }
                    "rollback" -> { rollbackCount++; null }
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val parameters = linkedMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setTimestamp", "setArray" -> parameters[args!![0] as Int] = args[1]
                    "setNull" -> parameters[args!![0] as Int] = null
                    "executeQuery" -> resultSet(query(sql, parameters))
                    "executeUpdate" -> update(sql, parameters)
                    "executeBatch" -> intArrayOf()
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun query(sql: String, parameters: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.startsWith("SELECT p.*") -> page?.takeIf { it.id == parameters[1] }?.let(::pageRow)?.let(::listOf) ?: emptyList()
            sql.startsWith("SELECT id FROM cms_pages ORDER") -> page?.let { buildList { add(mapOf<Any, Any?>(1 to it.id)); if (includeMissingListEntry) add(mapOf<Any, Any?>(1 to "missing-page")) } } ?: emptyList()
            sql.contains("SELECT id FROM cms_pages WHERE slug") -> if (page?.slug == parameters[1] && page?.status == CmsStatus.PUBLISHED) listOf(mapOf<Any, Any?>(1 to page!!.id)) else emptyList()
            sql.contains("SELECT content_json::text FROM cms_page_versions") -> versions[parameters[2] as? Int]?.let { listOf(mapOf<Any, Any?>(1 to it)) } ?: emptyList()
            sql.startsWith("SELECT id,page_id,version,state,unpublish_at") -> schedules.filter { it.state == "PENDING" || it.state == "PUBLISHED" }.map { mapOf<Any, Any?>(1 to it.id, 2 to it.pageId, 3 to it.version, 4 to it.state, 5 to it.unpublishAt?.let(Timestamp::from)) }
            sql.startsWith("SELECT id,aggregate_type") -> outbox.filter { !it.published }.map { mapOf<Any, Any?>(1 to it.id, 2 to "CmsPage", 3 to it.aggregateId, 4 to it.eventType, 5 to 1, 6 to Timestamp.from(clock), 7 to it.correlation, 8 to "{}") }
            else -> emptyList()
        }

        private fun update(sql: String, parameters: Map<Int, Any?>): Int {
            when {
                sql.startsWith("INSERT INTO cms_pages") -> {
                    page = FakePage(parameters[1].toString(), CmsStatus.DRAFT, 1, 1, parameters[2].toString(), parameters[3].toString(), parameters[5].toString())
                }
                sql.startsWith("INSERT INTO cms_page_versions") -> versions[parameters[3] as Int] = parameters[4].toString()
                sql.startsWith("INSERT INTO cms_schedules") -> schedules += FakeSchedule(parameters[1].toString(), parameters[2].toString(), parameters[3] as Int, "PENDING", (parameters[4] as Timestamp).toInstant(), (parameters[5] as? Timestamp)?.toInstant())
                sql.startsWith("INSERT INTO cms_outbox_events") -> {
                    if (failOutbox) throw SQLException("outbox unavailable", "23505")
                    outbox += FakeOutbox(parameters[1].toString(), parameters[3].toString(), parameters[4].toString())
                }
                sql.startsWith("UPDATE cms_pages SET slug") -> {
                    if (forceUpdateConflict) return 0
                    page?.apply { slug = parameters[1].toString(); title = parameters[2].toString(); currentVersion = parameters[3] as Int; version++ }
                }
                sql.startsWith("UPDATE cms_pages SET current_version") -> page?.apply { currentVersion = parameters[1] as Int; version++; status = CmsStatus.DRAFT }
                sql.contains("SET status='SCHEDULED'") -> page?.apply { status = CmsStatus.SCHEDULED; version++ }
                sql.contains("SET status='PUBLISHED'") -> page?.apply { status = CmsStatus.PUBLISHED; version++ }
                sql.contains("SET status=?,version") -> page?.apply { status = CmsStatus.valueOf(parameters[1].toString()); version++ }
                sql.startsWith("UPDATE cms_page_versions") -> Unit
                sql.startsWith("UPDATE cms_schedules SET state=?") -> schedules.firstOrNull { it.id == parameters[2].toString() }?.state = parameters[1].toString()
                sql.startsWith("UPDATE cms_schedules SET state='COMPLETED'") -> schedules.filter { it.pageId == parameters[1].toString() && it.state in setOf("PENDING", "PUBLISHED") }.forEach { it.state = "COMPLETED" }
                sql.startsWith("UPDATE cms_outbox_events") -> {
                    outbox.forEach { it.published = true }
                    publishedOutbox = true
                }
            }
            return 1
        }

        private fun pageRow(value: FakePage): Map<Any, Any?> = mapOf(
            "id" to value.id, "slug" to value.slug, "title" to value.title, "status" to value.status.name,
            "current_version" to value.currentVersion, "version" to value.version, "content_json" to (versions[value.currentVersion] ?: "{}"),
            "seo_json" to "{}", "created_by" to value.createdBy, "created_at" to Timestamp.from(clock), "updated_at" to Timestamp.from(clock),
        )

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key)
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getLong" -> (value(key) as? Number)?.toLong() ?: 0L
                    "getTimestamp" -> value(key) as? Timestamp
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        companion object {
            fun seed(status: CmsStatus, currentVersion: Int = 1, version: Long = 1) = FakeCmsDatabase(true, status, currentVersion, version)
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }

    private data class FakePage(
        val id: String,
        var status: CmsStatus,
        var currentVersion: Int,
        var version: Long,
        var slug: String = "page-slug",
        var title: String = "Page title",
        var createdBy: String = "admin-1",
    )

    private data class FakeSchedule(
        val id: String,
        val pageId: String,
        val version: Int,
        var state: String,
        val publishAt: Instant?,
        val unpublishAt: Instant? = null,
    )

    private data class FakeOutbox(val id: String, val aggregateId: String, val eventType: String, val correlation: String = "corr") {
        var published: Boolean = false
    }

}
