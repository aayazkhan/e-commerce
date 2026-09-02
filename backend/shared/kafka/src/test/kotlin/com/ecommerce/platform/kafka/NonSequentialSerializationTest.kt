package com.ecommerce.platform.kafka

import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class NonSequentialSerializationTest {
    @Test
    fun `event envelope decodes all required fields through a non sequential decoder`() {
        val values = mapOf<Int, Any?>(
            0 to "evt-1",
            1 to "OrderCreated",
            2 to 1,
            3 to "2026-08-20T00:00:00Z",
            4 to "order-service",
            5 to "tenant-1",
            6 to "Order",
            7 to "order-1",
            8 to "corr-1",
            10 to "{}",
        )
        val decoded = EventEnvelope.serializer().deserialize(
            ValuesDecoder(values, listOf(10, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        )

        assertEquals(EventEnvelope("evt-1", "OrderCreated", 1, "2026-08-20T00:00:00Z", "order-service", "tenant-1", "Order", "order-1", "corr-1", payloadJson = "{}"), decoded)
    }

    @Test
    fun `event envelope preserves an explicitly supplied optional causation id`() {
        val values = mapOf<Int, Any?>(
            0 to "evt-2", 1 to "OrderCreated", 2 to 1, 3 to "2026-08-20T00:00:00Z",
            4 to "order-service", 5 to "tenant-1", 6 to "Order", 7 to "order-1",
            8 to "corr-1", 9 to "cause-1", 10 to "{}",
        )
        val decoded = EventEnvelope.serializer().deserialize(
            ValuesDecoder(values, listOf(10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)),
        )

        assertEquals("cause-1", decoded.causationId)
        val encoded = Json.encodeToString(decoded)
        assertEquals(true, encoded.contains("\"causationId\":\"cause-1\""))
    }

    @Test
    fun `event envelope serializer rejects a missing required field from a non sequential decoder`() {
        val values = mapOf<Int, Any?>(
            0 to "evt-1",
            1 to "OrderCreated",
            2 to 1,
            3 to "2026-08-20T00:00:00Z",
            4 to "order-service",
            5 to "tenant-1",
            6 to "Order",
            7 to "order-1",
            8 to "corr-1",
            10 to "{}",
        )
        kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> {
            EventEnvelope.serializer().deserialize(
                ValuesDecoder(values - 8, listOf(10, 7, 6, 5, 4, 3, 2, 1, 0)),
            )
        }
    }

    private class ValuesDecoder(
        private val values: Map<Int, Any?>,
        private val order: List<Int>,
    ) : AbstractDecoder() {
        override val serializersModule = EmptySerializersModule()
        private var position = 0
        private var currentIndex = -1

        override fun decodeSequentially() = false

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            if (position < order.size) order[position++].also { currentIndex = it } else CompositeDecoder.DECODE_DONE

        override fun decodeNotNullMark(): Boolean = values[currentIndex] != null
        override fun decodeString(): String = values.getValue(currentIndex) as String
        override fun decodeInt(): Int = values.getValue(currentIndex) as Int

        @Suppress("UNCHECKED_CAST")
        override fun <T> decodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: kotlinx.serialization.DeserializationStrategy<T>,
            previousValue: T?,
        ): T {
            currentIndex = index
            return values.getValue(index) as T
        }
    }
}
