package com.ecommerce.platform.kafka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class EventEnvelopeTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private val base = EventEnvelope(
        eventId = "evt-1",
        eventType = "OrderCreated",
        schemaVersion = 1,
        occurredAt = "2026-08-20T00:00:00Z",
        producer = "order-service",
        tenantId = "default",
        aggregateType = "Order",
        aggregateId = "ord-1",
        correlationId = "req-1",
        payloadJson = "{}",
    )

    @Test
    fun `envelope carries the event contract`() {
        assertEquals("evt-1", base.eventId)
        assertEquals("OrderCreated", base.eventType)
        assertEquals(1, base.schemaVersion)
        assertEquals("default", base.tenantId)
    }

    @Test
    fun `envelope rejects missing identity fields`() {
        assertFailsWith<IllegalArgumentException> { base.copy(eventId = "") }
        assertFailsWith<IllegalArgumentException> { base.copy(eventId = "   ") }
        assertFailsWith<IllegalArgumentException> { base.copy(eventType = " ") }
        assertFailsWith<IllegalArgumentException> { base.copy(eventType = "") }
        assertFailsWith<IllegalArgumentException> { base.copy(schemaVersion = 0) }
        assertFailsWith<IllegalArgumentException> { base.copy(tenantId = "") }
        assertFailsWith<IllegalArgumentException> { base.copy(tenantId = "   ") }
    }

    @Test
    fun `envelope decoder rejects missing required fields`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<EventEnvelope>("{}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<EventEnvelope>("{\"eventId\":\"evt-1\"}")
        }
    }

    @Test
    fun `envelope decoder applies the same validation to serialized payloads`() {
        fun payload(eventId: String = "evt-1", eventType: String = "OrderCreated", schemaVersion: Int = 1, tenantId: String = "tenant-1") =
            "{" +
                "\"eventId\":\"$eventId\",\"eventType\":\"$eventType\",\"schemaVersion\":$schemaVersion," +
                "\"occurredAt\":\"2026-08-20T00:00:00Z\",\"producer\":\"order-service\"," +
                "\"tenantId\":\"$tenantId\",\"aggregateType\":\"Order\",\"aggregateId\":\"ord-1\"," +
                "\"correlationId\":\"req-1\",\"payloadJson\":\"{}\"}"

        assertFailsWith<IllegalArgumentException> { json.decodeFromString<EventEnvelope>(payload(eventId = "   ")) }
        assertFailsWith<IllegalArgumentException> { json.decodeFromString<EventEnvelope>(payload(eventType = "")) }
        assertFailsWith<IllegalArgumentException> { json.decodeFromString<EventEnvelope>(payload(schemaVersion = 0)) }
        assertFailsWith<IllegalArgumentException> { json.decodeFromString<EventEnvelope>(payload(tenantId = "   ")) }
    }

    @Test
    fun `envelope decoder rejects each missing required field`() {
        val fields = listOf(
            "eventId", "eventType", "schemaVersion", "occurredAt", "producer",
            "tenantId", "aggregateType", "aggregateId", "correlationId", "payloadJson",
        )
        val values = mapOf(
            "eventId" to "\"evt-1\"",
            "eventType" to "\"OrderCreated\"",
            "schemaVersion" to "1",
            "occurredAt" to "\"2026-08-20T00:00:00Z\"",
            "producer" to "\"order-service\"",
            "tenantId" to "\"tenant-1\"",
            "aggregateType" to "\"Order\"",
            "aggregateId" to "\"order-1\"",
            "correlationId" to "\"corr-1\"",
            "payloadJson" to "\"{}\"",
        )
        fields.forEach { missing ->
            val payload = values.filterKeys { it != missing }
                .entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"$key\":$value" }
            assertFailsWith<SerializationException>("missing $missing") {
                json.decodeFromString<EventEnvelope>(payload)
            }
        }
    }

    @Test
    fun `envelope serialization preserves optional causation and defaults`() {
        assertEquals(base, json.decodeFromString<EventEnvelope>(json.encodeToString(base)))
        val caused = base.copy(causationId = "evt-parent")
        assertEquals(caused, json.decodeFromString<EventEnvelope>(json.encodeToString(caused)))
        assertEquals("evt-parent", caused.causationId)

        val compact = Json { encodeDefaults = false; explicitNulls = false }
        val compactJson = compact.encodeToString(base)
        assertTrue(!compactJson.contains("causationId"))
        assertEquals(base, compact.decodeFromString<EventEnvelope>(compactJson))

        assertEquals("2026-08-20T00:00:00Z", base.occurredAt)
        assertEquals("order-service", base.producer)
        assertEquals("Order", base.aggregateType)
        assertEquals("ord-1", base.aggregateId)
        assertEquals("req-1", base.correlationId)
        assertEquals("{}", base.payloadJson)

        val explicitNulls = Json { encodeDefaults = true; explicitNulls = true }
        assertTrue(explicitNulls.encodeToString(base).contains("\"causationId\":null"))
        assertEquals(base, explicitNulls.decodeFromString<EventEnvelope>(explicitNulls.encodeToString(base)))
        assertFailsWith<SerializationException> {
            json.decodeFromString<EventEnvelope>("{\"eventId\":null,\"eventType\":\"OrderCreated\",\"schemaVersion\":1,\"occurredAt\":\"2026-08-20T00:00:00Z\",\"producer\":\"order-service\",\"tenantId\":\"tenant-1\",\"aggregateType\":\"Order\",\"aggregateId\":\"ord-1\",\"correlationId\":\"req-1\",\"payloadJson\":\"{}\"}")
        }
    }

    @Test
    fun `envelope copies preserve explicit and default fields`() {
        assertEquals(base.copy(), base)
        assertEquals(base.copy(causationId = "parent"), base.copy(causationId = "parent"))
        assertEquals(base.copy(eventType = "PaymentCaptured"), EventEnvelope(
            eventId = "evt-1",
            eventType = "PaymentCaptured",
            schemaVersion = 1,
            occurredAt = base.occurredAt,
            producer = base.producer,
            tenantId = base.tenantId,
            aggregateType = base.aggregateType,
            aggregateId = base.aggregateId,
            correlationId = base.correlationId,
            payloadJson = base.payloadJson,
        ))
    }

    @Test
    fun `envelope decoder accepts complete payload in a different field order`() {
        val outOfOrder = """
            {
              "payloadJson":"{}",
              "correlationId":"req-1",
              "aggregateId":"ord-1",
              "aggregateType":"Order",
              "tenantId":"default",
              "producer":"order-service",
              "occurredAt":"2026-08-20T00:00:00Z",
              "schemaVersion":1,
              "eventType":"OrderCreated",
              "eventId":"evt-1"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<EventEnvelope>(outOfOrder)
        assertEquals(base.eventId, decoded.eventId)
        assertEquals(base.eventType, decoded.eventType)
        assertEquals(base.schemaVersion, decoded.schemaVersion)
        assertEquals(base.occurredAt, decoded.occurredAt)
        assertEquals(base.producer, decoded.producer)
        assertEquals(base.tenantId, decoded.tenantId)
        assertEquals(base.aggregateType, decoded.aggregateType)
        assertEquals(base.aggregateId, decoded.aggregateId)
        assertEquals(base.correlationId, decoded.correlationId)
        assertEquals(base.causationId, decoded.causationId)
        assertEquals(base.payloadJson, decoded.payloadJson)
    }
}
