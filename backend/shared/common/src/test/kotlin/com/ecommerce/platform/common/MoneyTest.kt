package com.ecommerce.platform.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class MoneyTest {
    @Test
    fun `adds money in the same currency`() {
        assertEquals(Money(1500, "INR"), Money(1000, "INR") + Money(500, "INR"))
    }

    @Test
    fun `rejects cross currency arithmetic`() {
        assertFailsWith<IllegalArgumentException> {
            Money(100, "INR") + Money(100, "USD")
        }
    }

    @Test
    fun `supports subtraction validates currency and round trips`() {
        assertEquals(Money(500, "INR"), Money(1000, "INR") - Money(500, "INR"))
        assertFailsWith<IllegalArgumentException> { Money(100, "inr") }
        assertFailsWith<IllegalArgumentException> { Money(100, "INR") - Money(100, "USD") }
        val money = Money(-125, "USD")
        assertEquals(money, Json.decodeFromString<Money>(Json.encodeToString(money)))
        assertEquals(-125, money.amountMinor)
        assertEquals("USD", money.currency)
    }

    @Test
    fun `money copies preserve explicit and default fields`() {
        val original = Money(100, "INR")

        assertEquals(Money(100, "USD"), original.copy(currency = "USD"))
        assertEquals(Money(200, "INR"), original.copy(amountMinor = 200))
        assertEquals(original, original.copy())
        assertEquals(Money(300, "USD"), original.copy(amountMinor = 300, currency = "USD"))
    }

    @Test
    fun `money decoder rejects either missing required value`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Money>("{}")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Money>("{\"amountMinor\":100}")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Money>("{\"currency\":\"INR\"}")
        }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<Money>("{\"amountMinor\":100,\"currency\":\"inr\"}")
        }

        assertFailsWith<SerializationException> {
            Json.decodeFromString<Money>("{\"amountMinor\":null,\"currency\":\"INR\"}")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Money>("{\"amountMinor\":100,\"currency\":null}")
        }
    }

    @Test
    fun `money decoder accepts required fields in a different order`() {
        assertEquals(
            Money(100, "INR"),
            Json.decodeFromString<Money>("{\"currency\":\"INR\",\"amountMinor\":100}"),
        )
    }
}
