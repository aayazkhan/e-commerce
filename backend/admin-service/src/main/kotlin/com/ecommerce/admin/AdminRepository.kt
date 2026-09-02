package com.ecommerce.admin

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.ServiceOutboxRecord
import com.ecommerce.platform.service.ServiceOutboxStore
import com.ecommerce.platform.service.newOutboxId
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class AdminRepository(private val ds: DataSource, private val json: Json) : AdminStore, ServiceOutboxStore {
    override fun createJob(type: String, actor: String, request: BulkJobRequest, correlation: String): AdminJobResponse = transaction { connection ->
        val id = CommerceId.new("job").value
        val itemIds = request.itemIds.distinct()
        if (itemIds.isEmpty() || itemIds.size > 10_000 || itemIds.any { it.isBlank() }) throw ApiException(ErrorCode.VALIDATION_ERROR, "Bulk job must contain between 1 and 10,000 item IDs.", 400)
        runCatching { json.parseToJsonElement(request.payloadJson) }.getOrElse { throw ApiException(ErrorCode.VALIDATION_ERROR, "Bulk job payload must be valid JSON.", 400) }
        connection.prepareStatement("INSERT INTO admin_jobs(id,job_type,actor_id,payload_json,total_count) VALUES (?,?,?,?::jsonb,?)").use { statement ->
            statement.setString(1, id); statement.setString(2, type); statement.setString(3, actor); statement.setString(4, request.payloadJson); statement.setInt(5, itemIds.size); statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO admin_job_items(job_id,item_key) VALUES (?,?) ON CONFLICT DO NOTHING").use { statement ->
            itemIds.forEach { item -> statement.setString(1, id); statement.setString(2, item); statement.addBatch() }
            statement.executeBatch()
        }
        writeOutbox(connection, id, "AdminBulkJobCreated", correlation, actor)
        get(connection, id)!!
    }

    override fun get(id: String): AdminJobResponse? = ds.connection.use { connection -> get(connection, id) }

    fun claimItem(): JobItem? = transaction { connection ->
        connection.prepareStatement("SELECT i.id,i.job_id,i.item_key,j.job_type,j.actor_id FROM admin_job_items i JOIN admin_jobs j ON j.id=i.job_id WHERE (i.status='PENDING' OR (i.status='FAILED' AND i.attempts<5) OR (i.status='RUNNING' AND i.updated_at<now()-interval '2 minutes')) AND j.status IN ('PENDING','RUNNING') ORDER BY i.id FOR UPDATE SKIP LOCKED LIMIT 1").use { statement ->
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val item = JobItem(result.getLong(1), result.getString(2), result.getString(3), result.getString(4), result.getString(5))
                connection.prepareStatement("UPDATE admin_job_items SET status='RUNNING',attempts=attempts+1,updated_at=now() WHERE id=?").use { update -> update.setLong(1, item.id); update.executeUpdate() }
                connection.prepareStatement("UPDATE admin_jobs SET status='RUNNING',attempts=attempts+1,started_at=coalesce(started_at,now()) WHERE id=?").use { update -> update.setString(1, item.jobId); update.executeUpdate() }
                item
            }
        }
    }

    fun complete(item: JobItem, success: Boolean, error: String? = null) = transaction { connection ->
        connection.prepareStatement("UPDATE admin_job_items SET status=CASE WHEN ? THEN 'COMPLETED' WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,last_error=?,updated_at=now() WHERE id=?").use { statement ->
            statement.setBoolean(1, success); statement.setString(2, error?.take(1000)); statement.setLong(3, item.id); statement.executeUpdate()
        }
        val terminalFailure = !success && connection.prepareStatement("SELECT status='FAILED' FROM admin_job_items WHERE id=?").use { statement -> statement.setLong(1, item.id); statement.executeQuery().use { if (it.next()) it.getBoolean(1) else false } }
        connection.prepareStatement("UPDATE admin_jobs SET success_count=success_count+?,failure_count=failure_count+?,last_error=?,status=CASE WHEN EXISTS(SELECT 1 FROM admin_job_items WHERE job_id=? AND status='FAILED') AND NOT EXISTS(SELECT 1 FROM admin_job_items WHERE job_id=? AND status IN ('PENDING','RUNNING')) THEN 'FAILED' WHEN NOT EXISTS(SELECT 1 FROM admin_job_items WHERE job_id=? AND status IN ('PENDING','RUNNING')) THEN 'COMPLETED' ELSE 'RUNNING' END,completed_at=CASE WHEN NOT EXISTS(SELECT 1 FROM admin_job_items WHERE job_id=? AND status IN ('PENDING','RUNNING')) THEN now() ELSE completed_at END WHERE id=?").use { statement ->
            statement.setInt(1, if (success) 1 else 0); statement.setInt(2, if (terminalFailure) 1 else 0); statement.setString(3, error?.take(1000)); statement.setString(4, item.jobId); statement.setString(5, item.jobId); statement.setString(6, item.jobId); statement.setString(7, item.jobId); statement.setString(8, item.jobId); statement.executeUpdate()
        }
    }

    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = ds.connection.use { connection ->
        connection.prepareStatement("SELECT id,aggregate_type,aggregate_id,event_type,1,occurred_at,correlation_id,payload_json::text FROM admin_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement ->
            statement.setInt(1, limit); statement.executeQuery().use { result -> buildList { while (result.next()) add(ServiceOutboxRecord(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getInt(5), result.getTimestamp(6).toInstant(), result.getString(7), result.getString(8))) } }
        }
    }

    override fun markPublished(ids: List<String>, publishedAt: Instant) { if (ids.isEmpty()) return; ds.connection.use { connection -> connection.prepareStatement("UPDATE admin_outbox_events SET published_at=? WHERE id=ANY(?)").use { statement -> statement.setTimestamp(1, Timestamp.from(publishedAt)); statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray())); statement.executeUpdate() } } }
    private fun writeOutbox(connection: Connection, id: String, type: String, correlation: String, actor: String) = connection.prepareStatement("INSERT INTO admin_outbox_events(id,aggregate_type,aggregate_id,event_type,correlation_id,payload_json) VALUES (?,?,?,?,?,?::jsonb)").use { statement -> statement.setString(1, newOutboxId()); statement.setString(2, "AdminJob"); statement.setString(3, id); statement.setString(4, type); statement.setString(5, correlation); statement.setString(6, "{\"jobId\":\"$id\",\"actorId\":\"$actor\"}"); statement.executeUpdate() }
    private fun get(connection: Connection, id: String): AdminJobResponse? = connection.prepareStatement("SELECT * FROM admin_jobs WHERE id=?").use { statement -> statement.setString(1, id); statement.executeQuery().use { result -> if (!result.next()) null else AdminJobResponse(result.getString("id"), result.getString("job_type"), result.getString("status"), result.getInt("total_count"), result.getInt("success_count"), result.getInt("failure_count"), result.getInt("attempts"), result.getString("last_error"), result.getTimestamp("created_at").toInstant().toString(), result.getTimestamp("completed_at")?.toInstant()?.toString()) } }
    private fun <T> transaction(block: (Connection) -> T): T = ds.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } }
}

data class JobItem(val id: Long, val jobId: String, val itemKey: String, val jobType: String, val actorId: String)
