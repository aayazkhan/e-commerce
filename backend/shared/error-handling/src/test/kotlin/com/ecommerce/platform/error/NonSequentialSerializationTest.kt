package com.ecommerce.platform.error

import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class NonSequentialSerializationTest {
    @Test
    fun `error serializers decode required and optional values through a non sequential decoder`() {
        val violation = ValuesDecoder(mapOf(0 to "sku", 1 to "Required"), listOf(1, 0))
            .let { FieldViolation.serializer().deserialize(it) }
        assertEquals(FieldViolation("sku", "Required"), violation)

        val error = ValuesDecoder(
            mapOf(0 to ErrorCode.CONFLICT, 1 to "Conflict", 2 to "req-1", 4 to true),
            listOf(4, 2, 1, 0),
        ).let { ApiError.serializer().deserialize(it) }
        assertEquals(ApiError(ErrorCode.CONFLICT, "Conflict", "req-1", retryable = true), error)
    }

    @Test
    fun `error serializers apply optional defaults when omitted by a non sequential decoder`() {
        val decoded = ApiError.serializer().deserialize(
            ValuesDecoder(
                mapOf(0 to ErrorCode.NOT_FOUND, 1 to "Missing", 2 to "req-2"),
                listOf(2, 1, 0),
            ),
        )
        assertEquals(ApiError(ErrorCode.NOT_FOUND, "Missing", "req-2"), decoded)
    }

    @Test
    fun `error serializers reject missing required fields from a non sequential decoder`() {
        assertFailsWith<SerializationException> {
            FieldViolation.serializer().deserialize(ValuesDecoder(mapOf(1 to "Required"), listOf(1)))
        }
        assertFailsWith<SerializationException> {
            ApiError.serializer().deserialize(
                ValuesDecoder(
                    mapOf(1 to "Missing", 2 to "req-3"),
                    listOf(2, 1),
                ),
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
        override fun decodeBoolean(): Boolean = values.getValue(currentIndex) as Boolean

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
