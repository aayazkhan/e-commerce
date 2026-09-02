package com.ecommerce.search

import com.ecommerce.platform.service.ServiceDatabase
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

class SearchRepository(private val dataSource: DataSource) : SearchInboxStore {
    override fun processed(eventId: String): Boolean = dataSource.connection.use { connection -> connection.prepareStatement("SELECT 1 FROM search_inbox_events WHERE event_id=?").use { statement -> statement.setString(1, eventId); statement.executeQuery().use { it.next() } } }
    override fun record(eventId: String, eventType: String, aggregateId: String) = dataSource.connection.use { connection -> connection.prepareStatement("INSERT INTO search_inbox_events (event_id,event_type,aggregate_id,processed_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING").use { statement -> statement.setString(1, eventId); statement.setString(2, eventType); statement.setString(3, aggregateId); statement.setTimestamp(4, java.sql.Timestamp.from(Instant.now())); statement.executeUpdate() } }
}
