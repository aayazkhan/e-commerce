package com.ecommerce.category

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.ServiceOutboxRecord
import com.ecommerce.platform.service.ServiceOutboxStore
import com.ecommerce.platform.service.newOutboxId
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class CategoryInput(val parentId: String?, val name: String, val slug: String, val description: String?, val imageUrl: String?, val icon: String?, val sortOrder: Int, val status: CategoryStatus, val seoTitle: String?, val seoDescription: String?, val seoKeywords: List<String>, val canonicalUrl: String?)

class CategoryRepository(private val dataSource: DataSource) : ServiceOutboxStore, CategoryStore {
    private val json = Json { encodeDefaults = true }

    override fun list(parentId: String?, publicOnly: Boolean): List<Category> = withConnection { connection ->
        val statusClause = if (publicOnly) " AND status = 'ACTIVE'" else ""
        connection.prepareStatement("SELECT * FROM categories WHERE parent_id IS NOT DISTINCT FROM ?$statusClause ORDER BY sort_order, name, id").use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.category()) } }
        }
    }

    override fun find(id: String, publicOnly: Boolean): Category? = withConnection { connection ->
        connection.prepareStatement("SELECT * FROM categories WHERE id = ?${if (publicOnly) " AND status = 'ACTIVE'" else ""}").use { statement ->
            statement.setString(1, id); statement.executeQuery().use { result -> if (result.next()) result.category() else null }
        }
    }

    override fun tree(): List<Category> = withConnection { connection ->
        connection.prepareStatement("SELECT * FROM categories WHERE status = 'ACTIVE' ORDER BY path, sort_order, name, id").use { statement ->
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.category()) } }
        }
    }

    override fun create(input: CategoryInput, correlationId: String): Category = transaction { connection ->
        validateInput(input)
        val id = CommerceId.new("cat").value
        val now = Instant.now()
        val path = parentPath(connection, input.parentId) + id + "/"
        try {
            connection.prepareStatement("INSERT INTO categories (id,parent_id,name,slug,description,image_url,icon,sort_order,status,seo_title,seo_description,seo_keywords,canonical_url,path,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                statement.setString(1, id); statement.setString(2, input.parentId); statement.setString(3, input.name.trim()); statement.setString(4, input.slug); statement.setString(5, input.description); statement.setString(6, input.imageUrl); statement.setString(7, input.icon); statement.setInt(8, input.sortOrder); statement.setString(9, input.status.name); statement.setString(10, input.seoTitle); statement.setString(11, input.seoDescription); statement.setString(12, json.encodeToString(input.seoKeywords)); statement.setString(13, input.canonicalUrl); statement.setString(14, path); statement.setTimestamp(15, now.timestamp()); statement.setTimestamp(16, now.timestamp()); statement.executeUpdate()
            }
            val category = get(connection, id)!!
            outbox(connection, category, "CategoryCreated", correlationId, now)
            category
        } catch (error: Exception) {
            if (error is SQLException && error.sqlState == "23505") throw ApiException(ErrorCode.CONFLICT, "Category slug already exists.", 409)
            throw error
        }
    }

    override fun update(id: String, input: CategoryInput, correlationId: String): Category = transaction { connection ->
        validateInput(input)
        val existing = get(connection, id) ?: throw ApiException(ErrorCode.NOT_FOUND, "Category not found.", 404)
        if (input.parentId == id) throw ApiException(ErrorCode.VALIDATION_ERROR, "A category cannot be its own parent.", 400)
        if (input.parentId != null && get(connection, input.parentId) == null) throw ApiException(ErrorCode.VALIDATION_ERROR, "Parent category does not exist.", 400)
        if (input.parentId != null && get(connection, input.parentId)!!.path.startsWith(existing.path)) throw ApiException(ErrorCode.VALIDATION_ERROR, "Category hierarchy cannot contain a cycle.", 400)
        val now = Instant.now()
        val newPath = parentPath(connection, input.parentId) + id + "/"
        try {
            connection.prepareStatement("UPDATE categories SET parent_id=?,name=?,slug=?,description=?,image_url=?,icon=?,sort_order=?,status=?,seo_title=?,seo_description=?,seo_keywords=?,canonical_url=?,path=?,version=version+1,updated_at=?,published_at=CASE WHEN ? = 'ACTIVE' THEN COALESCE(published_at, ?) ELSE published_at END WHERE id=?").use { statement ->
                statement.setString(1, input.parentId); statement.setString(2, input.name.trim()); statement.setString(3, input.slug); statement.setString(4, input.description); statement.setString(5, input.imageUrl); statement.setString(6, input.icon); statement.setInt(7, input.sortOrder); statement.setString(8, input.status.name); statement.setString(9, input.seoTitle); statement.setString(10, input.seoDescription); statement.setString(11, json.encodeToString(input.seoKeywords)); statement.setString(12, input.canonicalUrl); statement.setString(13, newPath); statement.setTimestamp(14, now.timestamp()); statement.setString(15, input.status.name); statement.setTimestamp(16, now.timestamp()); statement.setString(17, id); statement.executeUpdate()
            }
            if (existing.path != newPath) connection.prepareStatement("UPDATE categories SET path = ? || substring(path from ?) , updated_at = ?, version = version + 1 WHERE path LIKE ? AND id <> ?").use { statement ->
                statement.setString(1, newPath); statement.setInt(2, existing.path.length + 1); statement.setTimestamp(3, now.timestamp()); statement.setString(4, existing.path + "%"); statement.setString(5, id); statement.executeUpdate()
            }
            val category = get(connection, id)!!
            outbox(connection, category, "CategoryUpdated", correlationId, now)
            category
        } catch (error: Exception) {
            if (error is SQLException && error.sqlState == "23505") throw ApiException(ErrorCode.CONFLICT, "Category slug already exists.", 409)
            throw error
        }
    }

    override fun delete(id: String, correlationId: String) = transaction { connection ->
        val category = get(connection, id) ?: throw ApiException(ErrorCode.NOT_FOUND, "Category not found.", 404)
        connection.prepareStatement("SELECT 1 FROM categories WHERE parent_id = ? LIMIT 1").use { statement ->
            statement.setString(1, id); statement.executeQuery().use { if (it.next()) throw ApiException(ErrorCode.CONFLICT, "Category with children cannot be deleted.", 409) }
        }
        connection.prepareStatement("DELETE FROM categories WHERE id = ?").use { statement -> statement.setString(1, id); statement.executeUpdate() }
        insertOutbox(connection, "CategoryDeleted", id, category, correlationId, Instant.now())
    }

    override fun changeStatus(id: String, status: CategoryStatus, correlationId: String): Category = transaction { connection ->
        val existing = get(connection, id) ?: throw ApiException(ErrorCode.NOT_FOUND, "Category not found.", 404)
        if (status == CategoryStatus.ACTIVE && existing.parentId != null && get(connection, existing.parentId)?.status != CategoryStatus.ACTIVE) throw ApiException(ErrorCode.CONFLICT, "A category cannot be published before its parent.", 409)
        val now = Instant.now()
        connection.prepareStatement("UPDATE categories SET status=?, updated_at=?, version=version+1, published_at=CASE WHEN ? = 'ACTIVE' THEN COALESCE(published_at, ?) ELSE published_at END WHERE id=?").use { statement -> statement.setString(1, status.name); statement.setTimestamp(2, now.timestamp()); statement.setString(3, status.name); statement.setTimestamp(4, now.timestamp()); statement.setString(5, id); statement.executeUpdate() }
        val category = get(connection, id)!!
        outbox(connection, category, "Category${status.name.replaceFirstChar { it.titlecase() }}", correlationId, now)
        category
    }

    override fun reorder(parentId: String?, ids: List<String>, correlationId: String): List<Category> = transaction { connection ->
        ids.forEachIndexed { index, id ->
            if (get(connection, id)?.parentId != parentId) throw ApiException(ErrorCode.VALIDATION_ERROR, "All categories must share the requested parent.", 400)
            connection.prepareStatement("UPDATE categories SET sort_order=?, version=version+1, updated_at=? WHERE id=?").use { statement -> statement.setInt(1, index); statement.setTimestamp(2, Instant.now().timestamp()); statement.setString(3, id); statement.executeUpdate() }
        }
        ids.mapNotNull { get(connection, it) }.also { categories -> categories.forEach { outbox(connection, it, "CategoryUpdated", correlationId, Instant.now()) } }
    }

    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = withConnection { connection ->
        connection.prepareStatement("SELECT id,aggregate_type,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json::text FROM category_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement -> statement.setInt(1, limit); statement.executeQuery().use { result -> buildList { while (result.next()) add(ServiceOutboxRecord(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getInt(5), result.getTimestamp(6).toInstant(), result.getString(7), result.getString(8))) } } }
    }

    override fun markPublished(ids: List<String>, publishedAt: Instant) {
        transaction { connection ->
            connection.prepareStatement("UPDATE category_outbox_events SET published_at=? WHERE id = ANY (?)").use { statement -> statement.setTimestamp(1, publishedAt.timestamp()); statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray())); statement.executeUpdate() }
        }
    }

    private fun outbox(connection: Connection, category: Category, eventType: String, correlationId: String, now: Instant) = insertOutbox(connection, eventType, category.id, category, correlationId, now)
    private fun insertOutbox(connection: Connection, eventType: String, aggregateId: String, category: Category, correlationId: String, now: Instant) = connection.prepareStatement("INSERT INTO category_outbox_events (id,aggregate_type,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json) VALUES (?,?,?,?,1,?,?,?::jsonb)").use { statement -> statement.setString(1, newOutboxId()); statement.setString(2, "Category"); statement.setString(3, aggregateId); statement.setString(4, eventType); statement.setTimestamp(5, now.timestamp()); statement.setString(6, correlationId); statement.setString(7, json.encodeToString(CategoryEventPayload(category.id, category.slug, category.status.name, category.version))); statement.executeUpdate() }
    private fun validateInput(input: CategoryInput) { if (input.name.trim().length !in 1..160) throw ApiException(ErrorCode.VALIDATION_ERROR, "Category name is invalid.", 400); validateSlug(input.slug); if (input.sortOrder < 0) throw ApiException(ErrorCode.VALIDATION_ERROR, "Sort order cannot be negative.", 400) }
    private fun parentPath(connection: Connection, parentId: String?): String { if (parentId == null) return "/"; val parent = get(connection, parentId) ?: throw ApiException(ErrorCode.VALIDATION_ERROR, "Parent category does not exist.", 400); if (parent.status == CategoryStatus.ARCHIVED) throw ApiException(ErrorCode.VALIDATION_ERROR, "An archived category cannot be a parent.", 400); return parent.path }
    private fun get(connection: Connection, id: String): Category? = connection.prepareStatement("SELECT * FROM categories WHERE id=?").use { statement -> statement.setString(1, id); statement.executeQuery().use { result -> if (result.next()) result.category() else null } }
    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } }
}

private fun ResultSet.category(): Category = Category(getString("id"), getString("parent_id"), getString("name"), getString("slug"), getString("description"), getString("image_url"), getString("icon"), getInt("sort_order"), CategoryStatus.valueOf(getString("status")), getString("seo_title"), getString("seo_description"), kotlinx.serialization.json.Json.decodeFromString(getString("seo_keywords")), getString("canonical_url"), getLong("version"), getTimestamp("created_at").toInstant().toString(), getTimestamp("updated_at").toInstant().toString(), getString("path"))
private fun Instant.timestamp(): Timestamp = Timestamp.from(this)
