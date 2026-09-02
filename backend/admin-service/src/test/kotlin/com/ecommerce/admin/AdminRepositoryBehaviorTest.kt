package com.ecommerce.admin

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.Json
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminRepositoryBehaviorTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `create job deduplicates items records outbox and returns persisted response`() {
        val database = FakeAdminDatabase()
        val repository = AdminRepository(database.dataSource(), Json.Default)

        val job = repository.createJob(
            "PRODUCT_PUBLISH",
            "admin-1",
            BulkJobRequest(listOf("product-1", "product-1", "product-2"), "{\"dryRun\":false}"),
            "corr-1"
        )

        assertEquals("PRODUCT_PUBLISH", job.jobType)
        assertEquals(2, job.totalCount)
        val persistedItems: List<String>? = database.items[job.id]
        assertEquals(listOf("product-1", "product-2"), persistedItems)
        assertEquals(1, database.outbox.size)
        assertEquals("AdminBulkJobCreated", database.outbox.single().eventType)
        assertEquals("corr-1", database.outbox.single().correlationId)
    }

    @Test
    fun `get and claim transition pending work and return a job item`() {
        val database = FakeAdminDatabase()
        val repository = AdminRepository(database.dataSource(), Json.Default)
        val job = repository.createJob("PRODUCT_PUBLISH", "admin-1", BulkJobRequest(listOf("product-1")), "corr-1")

        assertEquals(job, repository.get(job.id))
        val item = repository.claimItem()
        assertNotNull(item)
        assertEquals(job.id, item.jobId)
        assertEquals("product-1", item.itemKey)
        assertEquals("RUNNING", database.itemsById[item.id]!!.status)
        assertEquals("RUNNING", database.jobs[job.id]!!.status)
        assertEquals(1, database.itemsById[item.id]!!.attempts)
    }

    @Test
    fun `claiming an empty queue returns no work`() {
        val repository = AdminRepository(FakeAdminDatabase().dataSource(), Json.Default)

        assertNull(repository.claimItem())
    }

    @Test
    fun `successful completion marks item and job completed`() {
        val database = FakeAdminDatabase()
        val repository = AdminRepository(database.dataSource(), Json.Default)
        val job = repository.createJob("PRODUCT_PUBLISH", "admin-1", BulkJobRequest(listOf("product-1")), "corr-1")
        val item = assertNotNull(repository.claimItem())

        repository.complete(item, success = true)

        assertEquals("COMPLETED", database.itemsById[item.id]!!.status)
        assertEquals("COMPLETED", database.jobs[job.id]!!.status)
        assertEquals(1, database.jobs[job.id]!!.successCount)
        assertEquals(0, database.jobs[job.id]!!.failureCount)
        assertNotNull(database.jobs[job.id]!!.completedAt)
        assertNotNull(repository.get(job.id)?.completedAt)
    }

    @Test
    fun `failed completion is retryable and terminal failure is recorded`() {
        val database = FakeAdminDatabase()
        val repository = AdminRepository(database.dataSource(), Json.Default)
        val job = repository.createJob("PRODUCT_PUBLISH", "admin-1", BulkJobRequest(listOf("product-1")), "corr-1")
        val item = assertNotNull(repository.claimItem())

        repository.complete(item, success = false, error = "catalog unavailable")

        assertEquals("PENDING", database.itemsById[item.id]!!.status)
        assertEquals("RUNNING", database.jobs[job.id]!!.status)
        assertEquals(0, database.jobs[job.id]!!.failureCount)

        database.itemsById[item.id]!!.attempts = 5
        repository.complete(item, success = false, error = "permanent failure")

        assertEquals("FAILED", database.itemsById[item.id]!!.status)
        assertEquals("FAILED", database.jobs[job.id]!!.status)
        assertEquals(1, database.jobs[job.id]!!.failureCount)
        assertEquals("permanent failure", database.jobs[job.id]!!.lastError)
    }

    @Test
    fun `completion tolerates a missing status row while keeping the item retryable`() {
        val database = FakeAdminDatabase().also { it.omitStatusLookup = true }
        val repository = AdminRepository(database.dataSource(), Json.Default)
        val job = repository.createJob("PRODUCT_PUBLISH", "admin-1", BulkJobRequest(listOf("product-1")), "corr-1")
        val item = assertNotNull(repository.claimItem())

        repository.complete(item, success = false, error = "temporary lookup gap")

        assertEquals("PENDING", database.itemsById[item.id]!!.status)
        assertEquals("RUNNING", database.jobs[job.id]!!.status)
    }

    @Test
    fun `outbox listing and publishing handle empty and populated batches`() {
        val database = FakeAdminDatabase()
        val repository = AdminRepository(database.dataSource(), Json.Default)
        assertTrue(repository.unpublished(10).isEmpty())
        val job = repository.createJob("PRODUCT_PUBLISH", "admin-1", BulkJobRequest(listOf("product-1")), "corr-1")

        val events = repository.unpublished(10)
        assertEquals(1, events.size)
        assertEquals(job.id, events.single().aggregateId)
        repository.markPublished(emptyList(), now)
        repository.markPublished(events.map { it.id }, now)
        assertTrue(database.outbox.single().published)
        assertTrue(repository.unpublished(10).isEmpty())
        assertNull(repository.get("missing"))
    }

    @Test
    fun `transaction rolls back when persistence fails`() {
        val database = FakeAdminDatabase().also { it.failOnJobInsert = true }
        val repository = AdminRepository(database.dataSource(), Json.Default)

        val error = runCatching {
            repository.createJob("PRODUCT_PUBLISH", "admin-1", BulkJobRequest(listOf("product-1")), "corr-1")
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(database.rolledBack)
        assertTrue(database.outbox.isEmpty())
        assertEquals(ErrorCode.INTERNAL_ERROR, (error as? ApiException)?.errorCode ?: ErrorCode.INTERNAL_ERROR)
    }

    private class FakeAdminDatabase {
        private val fixedNow = Instant.parse("2026-08-20T00:00:00Z")
        val jobs = linkedMapOf<String, StoredJob>()
        val items = linkedMapOf<String, MutableList<String>>()
        val itemsById = linkedMapOf<Long, StoredItem>()
        val outbox = mutableListOf<StoredOutbox>()
        var nextItemId = 1L
        var failOnJobInsert = false
        var omitStatusLookup = false
        var rolledBack = false

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "createArrayOf" -> null
                    "setAutoCommit", "commit", "close" -> null
                    "rollback" -> { rolledBack = true; null }
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val params = linkedMapOf<Int, Any?>()
            val batch = mutableListOf<Map<Int, Any?>>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setTimestamp", "setBoolean", "setArray" -> params[args!![0] as Int] = args[1]
                    "addBatch" -> batch += params.toMap()
                    "executeBatch" -> { batch.forEach { update(sql, it) }; IntArray(batch.size) { 1 } }
                    "executeQuery" -> resultSet(query(sql, params))
                    "executeUpdate" -> update(sql, params)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun update(sql: String, params: Map<Int, Any?>): Int {
            when {
                sql.startsWith("INSERT INTO admin_jobs") -> {
                    if (failOnJobInsert) error("database unavailable")
                    val id = params[1].toString()
                    jobs[id] = StoredJob(id, params[2].toString(), params[3].toString(), params[5] as Int)
                }
                sql.startsWith("INSERT INTO admin_job_items") -> {
                    val jobId = params[1].toString()
                    val key = params[2].toString()
                    val itemId = nextItemId++
                    items.getOrPut(jobId) { mutableListOf() }.add(key)
                    itemsById[itemId] = StoredItem(itemId, jobId, key)
                }
                sql.startsWith("INSERT INTO admin_outbox_events") -> outbox += StoredOutbox(params[1].toString(), params[3].toString(), params[4].toString(), params[5].toString())
                sql.startsWith("UPDATE admin_job_items SET status='RUNNING'") -> {
                    val item = itemsById[params[1] as Long]!!
                    item.status = "RUNNING"; item.attempts++
                    jobs[item.jobId]!!.status = "RUNNING"; jobs[item.jobId]!!.attempts++
                }
                sql.startsWith("UPDATE admin_job_items SET status=CASE") -> {
                    val item = itemsById[params[3] as Long]!!
                    item.status = if (params[1] as Boolean) "COMPLETED" else if (item.attempts >= 5) "FAILED" else "PENDING"
                    item.lastError = params[2]?.toString()
                }
                sql.startsWith("UPDATE admin_jobs SET success_count") -> {
                    val job = jobs[params[8].toString()]!!
                    val item = itemsById.values.single { it.jobId == job.id }
                    if ((params[1] as Int) == 1) job.successCount++
                    if ((params[2] as Int) == 1) job.failureCount++
                    job.lastError = params[3]?.toString()
                    job.status = when {
                        item.status == "FAILED" -> "FAILED"
                        item.status !in listOf("PENDING", "RUNNING") -> "COMPLETED"
                        else -> "RUNNING"
                    }
                    if (job.status == "COMPLETED" || job.status == "FAILED") job.completedAt = fixedNow.toString()
                }
                sql.startsWith("UPDATE admin_outbox_events") -> outbox.forEach { it.published = true }
            }
            return 1
        }

        private fun query(sql: String, params: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.startsWith("SELECT * FROM admin_jobs") -> jobs[params[1].toString()]?.let { listOf(jobRow(it)) } ?: emptyList()
            sql.startsWith("SELECT i.id") -> itemsById.values.firstOrNull { it.status == "PENDING" && jobs[it.jobId]!!.status in listOf("PENDING", "RUNNING") }?.let { listOf(itemRow(it)) } ?: emptyList()
            sql.startsWith("SELECT status='FAILED'") -> if (omitStatusLookup) emptyList() else listOf(mapOf<Any, Any?>(1 to (itemsById[params[1] as Long]?.status == "FAILED")))
            sql.startsWith("SELECT id,aggregate_type") -> outbox.filter { !it.published }.map { mapOf<Any, Any?>(1 to it.id, 2 to "AdminJob", 3 to it.aggregateId, 4 to it.eventType, 5 to 1, 6 to Timestamp.from(fixedNow), 7 to it.correlationId, 8 to "{}") }
            else -> emptyList()
        }

        private fun jobRow(job: StoredJob) = mapOf<Any, Any?>("id" to job.id, "job_type" to job.type, "status" to job.status, "total_count" to job.totalCount, "success_count" to job.successCount, "failure_count" to job.failureCount, "attempts" to job.attempts, "last_error" to job.lastError, "created_at" to Timestamp.from(fixedNow), "completed_at" to job.completedAt?.let { Timestamp.from(Instant.parse(it)) })
        private fun itemRow(item: StoredItem) = mapOf<Any, Any?>(1 to item.id, 2 to item.jobId, 3 to item.key, 4 to jobs[item.jobId]!!.type, 5 to jobs[item.jobId]!!.actor)

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            var wasNull = false
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key).also { wasNull = it == null }
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getLong" -> (value(key) as? Number)?.toLong() ?: 0L
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getBoolean" -> value(key) as? Boolean ?: false
                    "getTimestamp" -> value(key) as? Timestamp
                    "wasNull" -> wasNull
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }

    private data class StoredJob(val id: String, val type: String, val actor: String, val totalCount: Int, var status: String = "PENDING", var successCount: Int = 0, var failureCount: Int = 0, var attempts: Int = 0, var lastError: String? = null, var completedAt: String? = null)
    private data class StoredItem(val id: Long, val jobId: String, val key: String, var status: String = "PENDING", var attempts: Int = 0, var lastError: String? = null)
    private data class StoredOutbox(val id: String, val aggregateId: String, val eventType: String, val correlationId: String, var published: Boolean = false)
}
