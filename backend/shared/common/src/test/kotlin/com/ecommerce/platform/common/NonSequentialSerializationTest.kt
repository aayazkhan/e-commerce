package com.ecommerce.platform.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class NonSequentialSerializationTest {
    @Test
    fun `common serializers decode through a non sequential composite decoder`() {
        val money = ValuesDecoder(mapOf(0 to 125L, 1 to "INR"), listOf(1, 0))
            .let { Money.serializer().deserialize(it) }
        assertEquals(Money(125, "INR"), money)

        val request = ValuesDecoder(mapOf(0 to "cursor-1", 1 to 7), listOf(1, 0))
            .let { CursorPageRequest.serializer().deserialize(it) }
        assertEquals(CursorPageRequest("cursor-1", 7), request)

        val page = ValuesDecoder(mapOf(0 to listOf("one"), 1 to "cursor-2", 2 to true), listOf(2, 1, 0))
            .let { CursorPage.serializer(String.serializer()).deserialize(it) }
        assertEquals(CursorPage(listOf("one"), "cursor-2", true), page)
    }

    @Test
    fun `common serializers reject missing required values from a non sequential decoder`() {
        assertFailsWith<SerializationException> {
            Money.serializer().deserialize(ValuesDecoder(mapOf(0 to 125L), listOf(0)))
        }
        assertFailsWith<SerializationException> {
            Money.serializer().deserialize(ValuesDecoder(mapOf(1 to "INR"), listOf(1)))
        }
        assertFailsWith<SerializationException> {
            CursorPage.serializer(String.serializer()).deserialize(ValuesDecoder(mapOf(0 to emptyList<String>(), 2 to false), listOf(0, 2)))
        }
        assertFailsWith<SerializationException> {
            CursorPage.serializer(String.serializer()).deserialize(ValuesDecoder(mapOf(1 to null, 2 to false), listOf(1, 2)))
        }
    }

    @Test
    fun `pagination request serializer uses defaults when all fields are omitted`() {
        assertEquals(
            CursorPageRequest(),
            CursorPageRequest.serializer().deserialize(ValuesDecoder(emptyMap(), emptyList())),
        )
    }

    @Test
    fun `pagination request serializer validates a supplied limit after applying defaults`() {
        assertFailsWith<IllegalArgumentException> {
            CursorPageRequest.serializer().deserialize(ValuesDecoder(mapOf(1 to 0), listOf(1)))
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
        override fun decodeLong(): Long = values.getValue(currentIndex) as Long
        override fun decodeInt(): Int = values.getValue(currentIndex) as Int
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
