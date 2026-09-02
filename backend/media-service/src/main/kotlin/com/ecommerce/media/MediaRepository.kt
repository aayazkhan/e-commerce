package com.ecommerce.media

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.ServiceOutboxRecord
import com.ecommerce.platform.service.ServiceOutboxStore
import com.ecommerce.platform.service.newOutboxId
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class MediaInput(val ownerId: String, val objectKey: String, val bucket: String, val originalFilename: String?, val contentType: String, val sizeBytes: Long, val checksum: String)

interface MediaStore {
    fun create(input: MediaInput, correlationId: String): MediaAsset
    fun markProcessing(id: String): Int
    fun find(id: String): MediaAsset?
    fun delete(id: String, actorId: String, correlationId: String): MediaAsset
}

interface MediaProcessingStore {
    fun markReady(id: String, width: Int?, height: Int?, correlationId: String): MediaAsset
    fun markFailed(id: String, error: String, correlationId: String): MediaAsset
    fun addVariant(mediaId: String, type: String, key: String, contentType: String, size: Long, width: Int?, height: Int?): Int
}

class MediaRepository(private val dataSource: DataSource) : ServiceOutboxStore, MediaStore, MediaProcessingStore {
    private val json = Json { encodeDefaults = true }

    override fun create(input: MediaInput, correlationId: String): MediaAsset = transaction { connection -> val id = CommerceId.new("med").value; val now = Instant.now(); connection.prepareStatement("INSERT INTO media_assets (id,owner_id,object_key,bucket,original_filename,content_type,size_bytes,checksum_sha256,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?, 'PENDING_UPLOAD', ?,?)").use { statement -> statement.setString(1, id); statement.setString(2, input.ownerId); statement.setString(3, input.objectKey); statement.setString(4, input.bucket); statement.setString(5, input.originalFilename?.take(255)); statement.setString(6, input.contentType); statement.setLong(7, input.sizeBytes); statement.setString(8, input.checksum); statement.setTimestamp(9, now.timestamp()); statement.setTimestamp(10, now.timestamp()); statement.executeUpdate() }; job(connection, id, now); val asset = get(connection, id)!!; outbox(connection, asset, "MediaUploadCreated", correlationId, now); asset }
    override fun markProcessing(id: String) = updateStatus(id, MediaStatus.PROCESSING)
    override fun markReady(id: String, width: Int?, height: Int?, correlationId: String): MediaAsset = transaction { connection -> val now = Instant.now(); connection.prepareStatement("UPDATE media_assets SET status='READY',width=?,height=?,updated_at=? WHERE id=?").use { statement -> statement.setIntOrNull(1, width); statement.setIntOrNull(2, height); statement.setTimestamp(3, now.timestamp()); statement.setString(4, id); statement.executeUpdate() }; connection.prepareStatement("UPDATE media_processing_jobs SET status='SUCCEEDED',updated_at=? WHERE media_id=?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setString(2, id); statement.executeUpdate() }; val asset = get(connection, id)!!; outbox(connection, asset, "MediaReady", correlationId, now); asset }
    override fun markFailed(id: String, error: String, correlationId: String): MediaAsset = transaction { connection -> val now = Instant.now(); connection.prepareStatement("UPDATE media_assets SET status='FAILED',updated_at=? WHERE id=?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setString(2, id); statement.executeUpdate() }; connection.prepareStatement("UPDATE media_processing_jobs SET status='FAILED',attempts=attempts+1,error_message=?,updated_at=? WHERE media_id=?").use { statement -> statement.setString(1, error.take(1000)); statement.setTimestamp(2, now.timestamp()); statement.setString(3, id); statement.executeUpdate() }; val asset = get(connection, id)!!; outbox(connection, asset, "MediaProcessingFailed", correlationId, now); asset }
    override fun addVariant(mediaId: String, type: String, key: String, contentType: String, size: Long, width: Int?, height: Int?) = transaction { connection -> connection.prepareStatement("INSERT INTO media_variants (id,media_id,variant_type,object_key,content_type,size_bytes,width,height,created_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (media_id,variant_type) DO UPDATE SET object_key=EXCLUDED.object_key,content_type=EXCLUDED.content_type,size_bytes=EXCLUDED.size_bytes,width=EXCLUDED.width,height=EXCLUDED.height").use { statement -> statement.setString(1, CommerceId.new("mvar").value); statement.setString(2, mediaId); statement.setString(3, type); statement.setString(4, key); statement.setString(5, contentType); statement.setLong(6, size); statement.setIntOrNull(7, width); statement.setIntOrNull(8, height); statement.setTimestamp(9, Instant.now().timestamp()); statement.executeUpdate() } }
    override fun find(id: String): MediaAsset? = withConnection { connection -> get(connection, id) }
    override fun delete(id: String, actorId: String, correlationId: String): MediaAsset = transaction { connection -> val asset = get(connection, id) ?: throw ApiException(ErrorCode.NOT_FOUND, "Media asset not found.", 404); if (asset.ownerId != actorId) throw ApiException(ErrorCode.FORBIDDEN, "You do not own this media asset.", 403); val now = Instant.now(); connection.prepareStatement("UPDATE media_assets SET status='DELETED',updated_at=? WHERE id=?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setString(2, id); statement.executeUpdate() }; val deleted = get(connection, id)!!; outbox(connection, deleted, "MediaDeleted", correlationId, now); deleted }
    private fun updateStatus(id: String, status: MediaStatus) = transaction { connection -> connection.prepareStatement("UPDATE media_assets SET status=?,updated_at=? WHERE id=?").use { statement -> statement.setString(1, status.name); statement.setTimestamp(2, Instant.now().timestamp()); statement.setString(3, id); statement.executeUpdate() } }
    private fun job(connection: Connection, id: String, now: Instant) = connection.prepareStatement("INSERT INTO media_processing_jobs (id,media_id,status,created_at,updated_at) VALUES (?,?, 'PENDING', ?,?)").use { statement -> statement.setString(1, CommerceId.new("mjob").value); statement.setString(2, id); statement.setTimestamp(3, now.timestamp()); statement.setTimestamp(4, now.timestamp()); statement.executeUpdate() }
    private fun outbox(connection: Connection, asset: MediaAsset, type: String, correlationId: String, now: Instant) = connection.prepareStatement("INSERT INTO media_outbox_events (id,aggregate_type,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json) VALUES (?,?,?,?,1,?,?,?::jsonb)").use { statement -> statement.setString(1, newOutboxId()); statement.setString(2, "Media"); statement.setString(3, asset.id); statement.setString(4, type); statement.setTimestamp(5, now.timestamp()); statement.setString(6, correlationId); statement.setString(7, json.encodeToString(MediaEventPayload(asset.id, asset.status.name, asset.objectKey))); statement.executeUpdate() }
    private fun get(connection: Connection, id: String): MediaAsset? = connection.prepareStatement("SELECT * FROM media_assets WHERE id=?").use { statement -> statement.setString(1, id); statement.executeQuery().use { result -> if (result.next()) { val variants = connection.prepareStatement("SELECT * FROM media_variants WHERE media_id=? ORDER BY variant_type").use { child -> child.setString(1, id); child.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.variant()) } } }; result.asset(variants) } else null } }
    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = withConnection { connection -> connection.prepareStatement("SELECT id,aggregate_type,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json::text FROM media_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement -> statement.setInt(1, limit); statement.executeQuery().use { result -> buildList { while (result.next()) add(ServiceOutboxRecord(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getInt(5), result.getTimestamp(6).toInstant(), result.getString(7), result.getString(8))) } } } }
    override fun markPublished(ids: List<String>, publishedAt: Instant) { transaction { connection -> connection.prepareStatement("UPDATE media_outbox_events SET published_at=? WHERE id=ANY(?)").use { statement -> statement.setTimestamp(1, publishedAt.timestamp()); statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray())); statement.executeUpdate() } } }
    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } }
}

private fun ResultSet.asset(variants: List<MediaVariant>) = MediaAsset(getString("id"), getString("owner_id"), getString("object_key"), getString("bucket"), getString("original_filename"), getString("content_type"), getLong("size_bytes"), getString("checksum_sha256"), getInt("width").takeUnless { wasNull() }, getInt("height").takeUnless { wasNull() }, MediaStatus.valueOf(getString("status")), getTimestamp("created_at").toInstant().toString(), getTimestamp("updated_at").toInstant().toString(), variants)
private fun ResultSet.variant() = MediaVariant(getString("id"), getString("media_id"), getString("variant_type"), getString("object_key"), getString("content_type"), getLong("size_bytes"), getInt("width").takeUnless { wasNull() }, getInt("height").takeUnless { wasNull() })
private fun java.sql.PreparedStatement.setIntOrNull(index: Int, value: Int?) { if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value) }
private fun Instant.timestamp(): Timestamp = Timestamp.from(this)
