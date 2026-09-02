package com.ecommerce.platform.kafka

import kotlinx.serialization.Serializable

@Serializable
data class EventEnvelope(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: String,
    val producer: String,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val correlationId: String,
    val causationId: String? = null,
    val payloadJson: String,
) {
    init {
        require(eventId.isNotBlank()) { "Event ID must not be blank" }
        require(eventType.isNotBlank()) { "Event type must not be blank" }
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(tenantId.isNotBlank()) { "Tenant ID must not be blank" }
    }
}

interface EventPublisher {
    suspend fun publish(topic: String, key: String, event: EventEnvelope)
}
