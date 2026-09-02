package com.ecommerce.audit

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.kafka.EventEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class AuditRepository(private val ds: DataSource, private val json: Json) {
    fun consume(event: EventEnvelope) = transaction { connection ->
        val fresh = connection.prepareStatement("INSERT INTO audit_inbox_events(event_id,event_type) VALUES (?,?) ON CONFLICT DO NOTHING").use { statement ->
            statement.setString(1, event.eventId); statement.setString(2, event.eventType); statement.executeUpdate() > 0
        }
        if (!fresh) return@transaction
        val safe = sanitize(runCatching { json.parseToJsonElement(event.payloadJson) }.getOrElse { JsonObject(emptyMap()) })
        insert(connection, AuditRequest(event.payloadActor(), "SYSTEM", event.eventType, event.aggregateType, event.aggregateId, afterJson = safe.toString(), reason = "kafka-event", traceId = event.correlationId), event.eventId)
    }

    fun record(request: AuditRequest): AuditResponse = transaction { connection -> insert(connection, request, CommerceId.new("audit").value) }

    fun get(id: String): AuditResponse? = ds.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM audit_events WHERE id=?").use { statement ->
            statement.setString(1, id); statement.executeQuery().use { if (it.next()) it.audit() else null }
        }
    }

    fun search(actor: String?, action: String?, resourceType: String?, resourceId: String?, sellerId: String?, traceId: String?, from: String?, to: String?, cursor: Int, limit: Int): AuditPage = ds.connection.use { connection ->
        val clauses = mutableListOf<String>(); val values = mutableListOf<Any>()
        if (actor != null) { clauses += "actor_id=?"; values += actor }
        if (action != null) { clauses += "action=?"; values += action }
        if (resourceType != null) { clauses += "resource_type=?"; values += resourceType }
        if (resourceId != null) { clauses += "resource_id=?"; values += resourceId }
        if (sellerId != null) { clauses += "seller_id=?"; values += sellerId }
        if (traceId != null) { clauses += "trace_id=?"; values += traceId }
        if (from != null) { clauses += "created_at>=?"; values += Timestamp.from(Instant.parse(from)) }
        if (to != null) { clauses += "created_at<?"; values += Timestamp.from(Instant.parse(to)) }
        val pageSize = limit.coerceIn(1, 100)
        val sql = "SELECT * FROM audit_events ${if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")} ORDER BY created_at DESC,id DESC OFFSET ? LIMIT ?"
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.setInt(values.size + 1, cursor.coerceAtLeast(0)); statement.setInt(values.size + 2, pageSize + 1)
            statement.executeQuery().use { result ->
                val all = buildList { while (result.next()) add(result.audit()) }
                AuditPage(all.take(pageSize), if (all.size > pageSize) (cursor + pageSize).toString() else null)
            }
        }
    }

    fun dlq(event: EventEnvelope?, error: Throwable) = ds.connection.use { connection ->
        connection.prepareStatement("INSERT INTO audit_dlq(event_id,error_text) VALUES (?,?)").use { statement ->
            statement.setString(1, event?.eventId); statement.setString(2, error.message?.take(2000) ?: "consumer failure"); statement.executeUpdate()
        }
    }

    private fun insert(connection: Connection, request: AuditRequest, id: String): AuditResponse {
        val cleanBefore = request.beforeJson?.let { sanitize(runCatching { json.parseToJsonElement(it) }.getOrElse { JsonObject(emptyMap()) }).toString() }
        val cleanAfter = request.afterJson?.let { sanitize(runCatching { json.parseToJsonElement(it) }.getOrElse { JsonObject(emptyMap()) }).toString() }
        connection.prepareStatement("INSERT INTO audit_events(id,actor_id,actor_type,action,resource_type,resource_id,seller_id,before_json,after_json,reason,request_id,trace_id,ip_hash,user_agent) VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)").use { statement ->
            statement.setString(1, id); statement.setString(2, request.actorId); statement.setString(3, request.actorType.take(40)); statement.setString(4, request.action.take(120)); statement.setString(5, request.resourceType.take(80)); statement.setString(6, request.resourceId.take(160)); statement.setString(7, request.sellerId); statement.setString(8, cleanBefore); statement.setString(9, cleanAfter); statement.setString(10, request.reason?.take(2000)); statement.setString(11, request.requestId); statement.setString(12, request.traceId); statement.setString(13, request.ip?.let(::hash)); statement.setString(14, request.userAgent?.take(500)); statement.executeUpdate()
        }
        return get(connection, id)!!
    }

    private fun get(connection: Connection, id: String): AuditResponse? = connection.prepareStatement("SELECT * FROM audit_events WHERE id=?").use { statement ->
        statement.setString(1, id); statement.executeQuery().use { if (it.next()) it.audit() else null }
    }

    private fun java.sql.ResultSet.audit() = AuditResponse(getString("id"), getString("actor_id"), getString("actor_type"), getString("action"), getString("resource_type"), getString("resource_id"), getString("seller_id"), getString("before_json"), getString("after_json"), getString("reason"), getString("request_id"), getString("trace_id"), getTimestamp("created_at").toInstant().toString())
    private fun EventEnvelope.payloadActor(): String? = runCatching { json.parseToJsonElement(payloadJson).jsonObject["actorId"]?.jsonPrimitive?.content }.getOrNull()
    private fun sanitize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> buildJsonObject { value.forEach { (key, element) -> if (key.lowercase() !in setOf("password", "passwordhash", "token", "jwt", "otp", "cvv", "secret", "cardnumber")) put(key, sanitize(element)) } }
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map(::sanitize))
        else -> value
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    private fun <T> transaction(block: (Connection) -> T): T = ds.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } }
}
