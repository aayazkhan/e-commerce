package com.ecommerce.cms

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.ServiceOutboxRecord
import com.ecommerce.platform.service.ServiceOutboxStore
import com.ecommerce.platform.service.newOutboxId
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class CmsRepository(
    private val ds: DataSource,
    private val json: Json,
) : ServiceOutboxStore, CmsStore {
    override fun create(actor: String, request: CmsPageRequest, correlation: String): CmsPageResponse = transaction { connection ->
        validate(request)
        val id = CommerceId.new("page").value
        val now = Instant.now()
        connection.prepareStatement("INSERT INTO cms_pages(id,slug,title,status,current_version,seo_json,created_by) VALUES (?,?,?,'DRAFT',1,?::jsonb,?)").use { statement ->
            statement.setString(1, id); statement.setString(2, request.slug); statement.setString(3, request.title.trim())
            statement.setString(4, json.encodeToString(request.seo)); statement.setString(5, actor); statement.executeUpdate()
        }
        saveVersion(connection, id, 1, request.contentJson, actor, now)
        val page = get(connection, id)!!
        writeOutbox(connection, id, "CmsCreated", correlation, page)
        page
    }

    override fun list(limit: Int): List<CmsPageResponse> = ds.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM cms_pages ORDER BY updated_at DESC LIMIT ?").use { statement ->
            statement.setInt(1, limit.coerceIn(1, 100))
            statement.executeQuery().use { result ->
                buildList { while (result.next()) get(connection, result.getString(1))?.let(::add) }
            }
        }
    }

    override fun get(id: String): CmsPageResponse? = ds.connection.use { connection -> get(connection, id) }

    override fun public(slug: String): CmsPageResponse? = ds.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM cms_pages WHERE slug=? AND status='PUBLISHED'").use { statement ->
            statement.setString(1, slug)
            statement.executeQuery().use { result -> if (result.next()) get(connection, result.getString(1)) else null }
        }
    }

    override fun update(id: String, actor: String, request: CmsPageRequest, correlation: String): CmsPageResponse = transaction { connection ->
        validate(request)
        val page = get(connection, id) ?: notFound()
        if (request.expectedVersion != null && request.expectedVersion != page.version) throw ApiException(ErrorCode.CONFLICT, "CMS page version conflict.", 409)
        val nextVersion = page.currentVersion + 1
        connection.prepareStatement("UPDATE cms_pages SET slug=?,title=?,status='DRAFT',current_version=?,version=version+1,seo_json=?::jsonb,updated_at=now() WHERE id=? AND version=?").use { statement ->
            statement.setString(1, request.slug); statement.setString(2, request.title.trim()); statement.setInt(3, nextVersion)
            statement.setString(4, json.encodeToString(request.seo)); statement.setString(5, id); statement.setLong(6, page.version)
            if (statement.executeUpdate() == 0) throw ApiException(ErrorCode.CONFLICT, "CMS page version conflict.", 409)
        }
        saveVersion(connection, id, nextVersion, request.contentJson, actor, Instant.now())
        val result = get(connection, id)!!
        writeOutbox(connection, id, "CmsUpdated", correlation, result)
        result
    }

    override fun submit(id: String, actor: String, correlation: String) = transition(id, CmsStatus.IN_REVIEW, actor, correlation)
    override fun approve(id: String, actor: String, correlation: String) = transition(id, CmsStatus.APPROVED, actor, correlation)
    override fun unpublish(id: String, actor: String, correlation: String) = transition(id, CmsStatus.UNPUBLISHED, actor, correlation)
    fun archive(id: String, actor: String, correlation: String) = transition(id, CmsStatus.ARCHIVED, actor, correlation)

    override fun publish(id: String, actor: String, request: CmsPublishRequest, correlation: String): CmsPageResponse = transaction { connection ->
        val page = get(connection, id) ?: notFound()
        if (page.status !in setOf(CmsStatus.APPROVED, CmsStatus.PUBLISHED, CmsStatus.SCHEDULED)) throw ApiException(ErrorCode.CONFLICT, "Only approved CMS content can be published.", 409)
        val version = request.version ?: page.currentVersion
        if (version != page.currentVersion) throw ApiException(ErrorCode.CONFLICT, "Only the current CMS version can be published.", 409)
        val publishAt = request.publishAt?.let(::parseInstant)
        if (publishAt != null && publishAt.isAfter(Instant.now())) {
            connection.prepareStatement("INSERT INTO cms_schedules(id,page_id,version,publish_at,unpublish_at,timezone) VALUES (?,?,?,?,?,?)").use { statement ->
                statement.setString(1, CommerceId.new("cmsjob").value); statement.setString(2, id); statement.setInt(3, version)
                statement.setTimestamp(4, Timestamp.from(publishAt)); statement.setTimestamp(5, request.unpublishAt?.let { Timestamp.from(parseInstant(it)) })
                statement.setString(6, request.timezone); statement.executeUpdate()
            }
            connection.prepareStatement("UPDATE cms_pages SET status='SCHEDULED',version=version+1,updated_at=now() WHERE id=?").use { statement ->
                statement.setString(1, id); statement.executeUpdate()
            }
            return@transaction get(connection, id)!!
        }
        connection.prepareStatement("UPDATE cms_pages SET status='PUBLISHED',version=version+1,updated_at=now() WHERE id=?").use { statement ->
            statement.setString(1, id); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE cms_page_versions SET published_by=?,published_at=now() WHERE page_id=? AND version=?").use { statement ->
            statement.setString(1, actor); statement.setString(2, id); statement.setInt(3, version); statement.executeUpdate()
        }
        val result = get(connection, id)!!
        writeOutbox(connection, id, "CmsPublished", correlation, result)
        result
    }

    override fun rollback(id: String, actor: String, request: CmsRollbackRequest, correlation: String): CmsPageResponse = transaction { connection ->
        val page = get(connection, id) ?: notFound()
        if (page.version != request.expectedVersion) throw ApiException(ErrorCode.CONFLICT, "CMS page version conflict.", 409)
        val content = connection.prepareStatement("SELECT content_json::text FROM cms_page_versions WHERE page_id=? AND version=?").use { statement ->
            statement.setString(1, id); statement.setInt(2, request.version)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ApiException(ErrorCode.NOT_FOUND, "CMS version not found.", 404)
                result.getString(1)
            }
        }
        val nextVersion = page.currentVersion + 1
        connection.prepareStatement("UPDATE cms_pages SET current_version=?,version=version+1,status='DRAFT',updated_at=now() WHERE id=?").use { statement ->
            statement.setInt(1, nextVersion); statement.setString(2, id); statement.executeUpdate()
        }
        saveVersion(connection, id, nextVersion, content, actor, Instant.now())
        val result = get(connection, id)!!
        writeOutbox(connection, id, "CmsRolledBack", correlation, result)
        result
    }

    fun publishDue(): Int = transaction { connection ->
        var count = 0
        connection.prepareStatement("SELECT id,page_id,version,state,unpublish_at FROM cms_schedules WHERE (state='PENDING' AND publish_at<=now()) OR (state='PUBLISHED' AND unpublish_at<=now()) ORDER BY publish_at FOR UPDATE SKIP LOCKED LIMIT 50").use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val scheduleId = result.getString(1); val pageId = result.getString(2); val version = result.getInt(3); val state = result.getString(4)
                    val publishing = state == "PENDING"
                    connection.prepareStatement("UPDATE cms_pages SET status=?,version=version+1,updated_at=now() WHERE id=?").use { update ->
                        update.setString(1, if (publishing) CmsStatus.PUBLISHED.name else CmsStatus.UNPUBLISHED.name); update.setString(2, pageId); update.executeUpdate()
                    }
                    if (publishing) connection.prepareStatement("UPDATE cms_page_versions SET published_by='cms-scheduler',published_at=now() WHERE page_id=? AND version=?").use { update ->
                        update.setString(1, pageId); update.setInt(2, version); update.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE cms_schedules SET state=?,processed_at=now() WHERE id=?").use { update ->
                        update.setString(1, if (publishing && result.getTimestamp(5) != null) "PUBLISHED" else "COMPLETED"); update.setString(2, scheduleId); update.executeUpdate()
                    }
                    val page = get(connection, pageId) ?: continue
                    writeOutbox(connection, pageId, if (publishing) "CmsPublished" else "CmsUnpublished", scheduleId, page)
                    count++
                }
            }
        }
        count
    }

    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = ds.connection.use { connection ->
        connection.prepareStatement("SELECT id,aggregate_type,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json::text FROM cms_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(ServiceOutboxRecord(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getInt(5), result.getTimestamp(6).toInstant(), result.getString(7), result.getString(8)))
                }
            }
        }
    }

    override fun markPublished(ids: List<String>, publishedAt: Instant) {
        if (ids.isEmpty()) return
        ds.connection.use { connection ->
            connection.prepareStatement("UPDATE cms_outbox_events SET published_at=? WHERE id=ANY(?)").use { statement ->
                statement.setTimestamp(1, Timestamp.from(publishedAt)); statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray())); statement.executeUpdate()
            }
        }
    }

    private fun transition(id: String, status: CmsStatus, actor: String, correlation: String): CmsPageResponse = transaction { connection ->
        val page = get(connection, id) ?: notFound()
        if (!validTransition(page.status, status)) throw ApiException(ErrorCode.CONFLICT, "Invalid CMS lifecycle transition.", 409)
        connection.prepareStatement("UPDATE cms_pages SET status=?,version=version+1,updated_at=now() WHERE id=?").use { statement ->
            statement.setString(1, status.name); statement.setString(2, id); statement.executeUpdate()
        }
        if (status == CmsStatus.UNPUBLISHED || status == CmsStatus.ARCHIVED) connection.prepareStatement("UPDATE cms_schedules SET state='COMPLETED',processed_at=now() WHERE page_id=? AND state IN ('PENDING','PUBLISHED')").use { statement ->
            statement.setString(1, id); statement.executeUpdate()
        }
        val result = get(connection, id)!!
        writeOutbox(connection, id, "Cms${status.name.replaceFirstChar { it.uppercase() }}", correlation, result)
        result
    }

    private fun saveVersion(connection: Connection, pageId: String, version: Int, content: String, actor: String, at: Instant) {
        val clean = sanitizeHtml(content)
        val hash = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        connection.prepareStatement("INSERT INTO cms_page_versions(id,page_id,version,content_json,content_hash,created_by,created_at) VALUES (?,?,?,?::jsonb,?,?,?)").use { statement ->
            statement.setString(1, CommerceId.new("pgv").value); statement.setString(2, pageId); statement.setInt(3, version); statement.setString(4, clean)
            statement.setString(5, hash); statement.setString(6, actor); statement.setTimestamp(7, Timestamp.from(at)); statement.executeUpdate()
        }
    }

    private fun get(connection: Connection, id: String): CmsPageResponse? = connection.prepareStatement("SELECT p.*,v.content_json::text FROM cms_pages p JOIN cms_page_versions v ON v.page_id=p.id AND v.version=p.current_version WHERE p.id=?").use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { result ->
            if (!result.next()) return@use null
            CmsPageResponse(result.getString("id"), result.getString("slug"), result.getString("title"), CmsStatus.valueOf(result.getString("status")), result.getInt("current_version"), result.getLong("version"), result.getString("content_json"), json.decodeFromString(result.getString("seo_json")), result.getString("created_by"), result.getTimestamp("created_at").toInstant().toString(), result.getTimestamp("updated_at").toInstant().toString())
        }
    }

    private fun validate(request: CmsPageRequest) {
        if (!request.slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) || request.title.isBlank() || request.contentJson.isBlank()) throw ApiException(ErrorCode.VALIDATION_ERROR, "CMS page is invalid.", 400)
        runCatching { json.parseToJsonElement(request.contentJson) }.getOrElse { throw ApiException(ErrorCode.VALIDATION_ERROR, "contentJson must be valid JSON.", 400) }
    }

    private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { throw ApiException(ErrorCode.VALIDATION_ERROR, "Timestamp must be ISO-8601.", 400) }

    private fun validTransition(from: CmsStatus, to: CmsStatus): Boolean = when (from) {
        CmsStatus.DRAFT -> to in setOf(CmsStatus.IN_REVIEW, CmsStatus.ARCHIVED)
        CmsStatus.IN_REVIEW -> to in setOf(CmsStatus.APPROVED, CmsStatus.DRAFT)
        CmsStatus.APPROVED -> to in setOf(CmsStatus.PUBLISHED, CmsStatus.SCHEDULED, CmsStatus.DRAFT)
        CmsStatus.PUBLISHED -> to in setOf(CmsStatus.UNPUBLISHED, CmsStatus.ARCHIVED)
        CmsStatus.SCHEDULED -> to in setOf(CmsStatus.UNPUBLISHED, CmsStatus.ARCHIVED)
        CmsStatus.UNPUBLISHED -> to in setOf(CmsStatus.IN_REVIEW, CmsStatus.ARCHIVED)
        CmsStatus.ARCHIVED -> false
    }

    private fun writeOutbox(connection: Connection, aggregate: String, type: String, correlation: String, response: CmsPageResponse) {
        connection.prepareStatement("INSERT INTO cms_outbox_events(id,aggregate_type,aggregate_id,event_type,occurred_at,correlation_id,payload_json) VALUES (?,?,?,?,?,?,?::jsonb)").use { statement ->
            statement.setString(1, newOutboxId()); statement.setString(2, "CmsPage"); statement.setString(3, aggregate); statement.setString(4, type)
            statement.setTimestamp(5, Timestamp.from(Instant.now())); statement.setString(6, correlation); statement.setString(7, json.encodeToString(response)); statement.executeUpdate()
        }
    }

    private fun notFound(): Nothing = throw ApiException(ErrorCode.NOT_FOUND, "CMS page not found.", 404)

    private fun <T> transaction(block: (Connection) -> T): T = ds.connection.use { connection ->
        connection.autoCommit = false
        try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error }
    }
}
