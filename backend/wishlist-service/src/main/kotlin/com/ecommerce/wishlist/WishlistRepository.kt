package com.ecommerce.wishlist

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.ServiceOutboxRecord
import com.ecommerce.platform.service.ServiceOutboxStore
import com.ecommerce.platform.service.newOutboxId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

data class WishlistList(val records: List<WishlistRecord>, val nextCursor: String?, val hasMore: Boolean)

interface WishlistRouteStore {
    fun add(userId: String, productId: String, variantId: String, correlationId: String): WishlistRecord
    fun remove(userId: String, variantId: String, correlationId: String): Boolean
    fun clear(userId: String, correlationId: String): Int
    fun list(userId: String, cursor: String?, limit: Int): WishlistList
}

class WishlistRepository(private val dataSource: DataSource) : ServiceOutboxStore, WishlistRouteStore {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    override fun add(userId: String, productId: String, variantId: String, correlationId: String): WishlistRecord = transaction { connection ->
        val now = Instant.now()
        connection.prepareStatement("INSERT INTO wishlists(user_id,created_at,updated_at) VALUES (?,?,?) ON CONFLICT (user_id) DO NOTHING").use { statement -> statement.setString(1, userId); statement.setTimestamp(2, now.timestamp()); statement.setTimestamp(3, now.timestamp()); statement.executeUpdate() }
        val id = CommerceId.new("wish").value
        val inserted = connection.prepareStatement("INSERT INTO wishlist_items(id,user_id,product_id,variant_id,created_at) VALUES (?,?,?,?,?) ON CONFLICT (user_id,variant_id) DO NOTHING").use { statement -> statement.setString(1, id); statement.setString(2, userId); statement.setString(3, productId); statement.setString(4, variantId); statement.setTimestamp(5, now.timestamp()); statement.executeUpdate() }
        val record = connection.prepareStatement("SELECT * FROM wishlist_items WHERE user_id=? AND variant_id=?").use { statement -> statement.setString(1, userId); statement.setString(2, variantId); statement.executeQuery().use { if (it.next()) it.record() else throw ApiException(ErrorCode.INTERNAL_ERROR, "Wishlist item could not be read.", 500) } }
        if (inserted == 1) { touch(connection, userId, now); outbox(connection, record, "WishlistItemAdded", correlationId, now) }
        record
    }

    override fun remove(userId: String, variantId: String, correlationId: String): Boolean = transaction { connection ->
        val deleted = connection.prepareStatement("DELETE FROM wishlist_items WHERE user_id=? AND variant_id=?").use { statement -> statement.setString(1, userId); statement.setString(2, variantId); statement.executeUpdate() }
        if (deleted == 1) { val now = Instant.now(); touch(connection, userId, now); outbox(connection, WishlistEvent(userId, variantId), "WishlistItemRemoved", correlationId, now) }
        deleted == 1
    }

    override fun clear(userId: String, correlationId: String): Int = transaction { connection ->
        val deleted = connection.prepareStatement("DELETE FROM wishlist_items WHERE user_id=?").use { statement -> statement.setString(1, userId); statement.executeUpdate() }
        if (deleted > 0) { val now = Instant.now(); touch(connection, userId, now); outbox(connection, WishlistEvent(userId, null), "WishlistCleared", correlationId, now) }
        deleted
    }

    override fun list(userId: String, cursor: String?, limit: Int): WishlistList = withConnection { connection ->
        val size = limit.coerceIn(1, 50)
        val decoded = cursor?.let(::decodeCursor)
        val sql = if (decoded == null) "SELECT * FROM wishlist_items WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ?" else "SELECT * FROM wishlist_items WHERE user_id=? AND (created_at,id) < (?,?) ORDER BY created_at DESC,id DESC LIMIT ?"
        val records = connection.prepareStatement(sql).use { statement -> statement.setString(1, userId); var index = 2; if (decoded != null) { statement.setTimestamp(index++, Timestamp.from(decoded.first)); statement.setString(index++, decoded.second) }; statement.setInt(index, size + 1); statement.executeQuery().use { result -> buildList { while (result.next()) add(result.record()) } } }
        val more = records.size > size; val page = records.take(size); WishlistList(page, if (more) encodeCursor(Instant.parse(page.last().createdAt), page.last().id) else null, more)
    }

    private fun touch(connection: Connection, userId: String, now: Instant) = connection.prepareStatement("UPDATE wishlists SET version=version+1,updated_at=? WHERE user_id=?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setString(2, userId); statement.executeUpdate() }
    private fun outbox(connection: Connection, record: WishlistRecord, type: String, correlationId: String, now: Instant) = outbox(connection, WishlistEvent(record.userId, record.variantId, record.productId, record.id), type, correlationId, now)
    private fun outbox(connection: Connection, payload: WishlistEvent, type: String, correlationId: String, now: Instant) = connection.prepareStatement("INSERT INTO wishlist_outbox_events(id,aggregate_id,event_type,occurred_at,correlation_id,payload_json) VALUES (?,?,?,?,?,?::jsonb)").use { statement -> statement.setString(1, newOutboxId()); statement.setString(2, payload.userId); statement.setString(3, type); statement.setTimestamp(4, now.timestamp()); statement.setString(5, correlationId); statement.setString(6, json.encodeToString(payload)); statement.executeUpdate() }
    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = withConnection { connection -> connection.prepareStatement("SELECT id,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json::text FROM wishlist_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement -> statement.setInt(1, limit); statement.executeQuery().use { result -> buildList { while (result.next()) add(ServiceOutboxRecord(result.getString(1), "Wishlist", result.getString(2), result.getString(3), result.getInt(4), result.getTimestamp(5).toInstant(), result.getString(6), result.getString(7))) } } } }
    override fun markPublished(ids: List<String>, publishedAt: Instant) { if (ids.isEmpty()) return; transaction { connection -> connection.prepareStatement("UPDATE wishlist_outbox_events SET published_at=? WHERE id=ANY(?)").use { statement -> statement.setTimestamp(1, publishedAt.timestamp()); statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray())); statement.executeUpdate() } } }
    private fun decodeCursor(value: String): Pair<Instant, String> = try { val parts = String(Base64.getUrlDecoder().decode(value)).split('|', limit = 2); Instant.parse(parts[0]) to parts[1] } catch (_: Exception) { throw ApiException(ErrorCode.VALIDATION_ERROR, "Cursor is invalid.", 400) }
    private fun encodeCursor(createdAt: Instant, id: String) = Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$id".toByteArray())
    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } }
}

@Serializable
internal data class WishlistEvent(val userId: String, val variantId: String?, val productId: String? = null, val itemId: String? = null)
private fun ResultSet.record() = WishlistRecord(getString("id"), getString("user_id"), getString("product_id"), getString("variant_id"), getTimestamp("created_at").toInstant().toString())
private fun Instant.timestamp() = Timestamp.from(this)
