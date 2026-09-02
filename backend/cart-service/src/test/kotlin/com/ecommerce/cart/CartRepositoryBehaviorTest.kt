package com.ecommerce.cart

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CartRepositoryBehaviorTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val price = PriceSnapshot(1_000, "INR", "v1")
    private val actor = CartActor(userId = "user-1")

    @Test
    fun `get or create reuses carts rejects currency changes and maps duplicate inserts`() {
        val createdDatabase = FakeCartDatabase()
        val created = CartRepository(createdDatabase.dataSource()).getOrCreate(actor, "inr")
        assertEquals("INR", created.currency)
        assertEquals(1, createdDatabase.carts.size)
        assertEquals(created, CartRepository(createdDatabase.dataSource()).getOrCreate(actor, "INR"))

        val currencyError = assertFailsWith<ApiException> {
            CartRepository(createdDatabase.dataSource()).getOrCreate(actor, "USD")
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, currencyError.errorCode)

        val duplicateDatabase = FakeCartDatabase().also { it.duplicateCartInsert = true }
        val duplicate = CartRepository(duplicateDatabase.dataSource()).getOrCreate(actor, "INR")
        assertEquals("INR", duplicate.currency)

        val guest = CartRepository(FakeCartDatabase().dataSource()).getOrCreate(CartActor(guestToken = "guest-token"), "INR")
        assertNull(guest.userId)

        val unavailable = FakeCartDatabase().also { it.cartInsertFailure = SQLException("cart database unavailable", "08001") }
        assertFailsWith<SQLException> {
            CartRepository(unavailable.dataSource()).getOrCreate(actor, "INR")
        }
    }

    @Test
    fun `mutations update quantities preserve idempotency and reject conflicting requests`() {
        val database = FakeCartDatabase.seed()
        val repository = CartRepository(database.dataSource())

        val added = repository.add(actor, "product-1", "variant-1", 2, price, "add-1", "corr-1")
        assertEquals(3, added.items.single().quantity)
        val replay = repository.add(actor, "product-1", "variant-1", 2, price, "add-1", "corr-1")
        assertEquals(added, replay)

        val conflict = assertFailsWith<ApiException> {
            repository.add(actor, "product-1", "variant-1", 3, price, "add-1", "corr-2")
        }
        assertEquals(ErrorCode.CONFLICT, conflict.errorCode)

        val updated = repository.update(actor, "variant-1", 5, null, "update-1", "corr-3")
        assertEquals(5, updated.items.single().quantity)
        val repriced = repository.update(actor, "variant-1", 6, PriceSnapshot(1_250, "INR", "v2"), "update-2", "corr-3b")
        assertEquals(6, repriced.items.single().quantity)
        assertEquals(1_250, repriced.items.single().unitPriceMinor)
        assertEquals("v2", repriced.items.single().priceVersion)
        val removed = repository.remove(actor, "variant-1", "INR", "remove-1", "corr-4")
        assertTrue(removed.items.isEmpty())
        val cleared = repository.clear(actor, "INR", "clear-1", "corr-5")
        assertTrue(cleared.items.isEmpty())
    }

    @Test
    fun `guest mutation creates a guest cart and merge caps duplicate quantities`() {
        val database = FakeCartDatabase()
        val repository = CartRepository(database.dataSource())
        val guest = repository.add(
            CartActor(guestToken = "guest-token"),
            "product-guest",
            "variant-guest",
            2,
            price,
            "guest-add",
            "guest-correlation",
        )

        assertEquals(null, guest.userId)
        assertEquals(2, guest.items.single().quantity)

        database.carts += StoredCart("user-merge", "user-1", null, "INR")
        database.items += StoredItem("user-item", "user-merge", "product-1", "variant-1", 5, 700, "INR", "v-user")
        database.carts += StoredCart("guest-merge", null, hash("merge-token"), "INR")
        database.items += StoredItem("guest-item", "guest-merge", "product-1", "variant-1", 99, 500, "INR", "v-old")
        val merged = repository.merge("user-1", "merge-token", "INR", "merge-correlation")

        assertEquals(99, merged.items.single { it.variantId == "variant-1" }.quantity)
        assertTrue(database.carts.none { it.id == "guest-merge" })
    }

    @Test
    fun `merge creates a user cart when only the guest cart exists`() {
        val database = FakeCartDatabase()
        database.carts += StoredCart("guest-only", null, hash("guest-only-token"), "INR")
        database.items += StoredItem("guest-only-item", "guest-only", "product-2", "variant-2", 4, 500, "INR", "v2")

        val merged = CartRepository(database.dataSource()).merge("new-user", "guest-only-token", "INR", "merge-correlation")

        assertEquals("new-user", merged.userId)
        assertEquals(4, merged.items.single().quantity)
        assertTrue(database.carts.none { it.id == "guest-only" })
    }

    @Test
    fun `mutation rejects blank and oversized idempotency keys before changing the cart`() {
        val repository = CartRepository(FakeCartDatabase.seed().dataSource())

        assertError(ErrorCode.VALIDATION_ERROR) { repository.clear(actor, "INR", " ", "invalid") }
        assertError(ErrorCode.VALIDATION_ERROR) { repository.clear(actor, "INR", "x".repeat(129), "invalid") }
    }

    @Test
    fun `mutations validate quantities product ownership missing items and currencies`() {
        val database = FakeCartDatabase.seed()
        val repository = CartRepository(database.dataSource())

        assertError(ErrorCode.VALIDATION_ERROR) { repository.add(actor, "product-1", "variant-1", 0, price, "bad-1", "corr") }
        assertError(ErrorCode.VALIDATION_ERROR) { repository.add(actor, "product-1", "variant-1", 100, price, "bad-2", "corr") }
        assertError(ErrorCode.CONFLICT) { repository.add(actor, "other-product", "variant-1", 1, price, "bad-3", "corr") }
        assertError(ErrorCode.VALIDATION_ERROR) { repository.update(actor, "variant-1", 1, PriceSnapshot(1_000, "USD", "v1"), "bad-4", "corr") }
        assertError(ErrorCode.NOT_FOUND) { repository.update(actor, "missing", 1, null, "bad-5", "corr") }
        assertError(ErrorCode.VALIDATION_ERROR) { repository.remove(actor, "variant-1", "USD", "bad-6", "corr") }
    }

    @Test
    fun `abandon expired carts emits events and merge combines guest quantities`() {
        val expired = FakeCartDatabase.seed().also { it.carts += StoredCart("expired", null, hash("guest-expired"), "INR", expiresAt = now.minusSeconds(1)) }
        val expiredRepository = CartRepository(expired.dataSource())
        assertEquals(1, expiredRepository.abandonExpired(0, "abandon-corr"))
        assertEquals("ABANDONED", expired.carts.first { it.id == "expired" }.status)

        val database = FakeCartDatabase.seed()
        database.carts += StoredCart("guest-cart", null, hash("guest-token"), "INR")
        database.items += StoredItem("guest-item", "guest-cart", "product-2", "variant-2", 4, 500, "INR", "v2")
        val merged = CartRepository(database.dataSource()).merge("user-1", "guest-token", "INR", "merge-corr")
        assertEquals(2, merged.items.size)
        assertEquals(4, merged.items.first { it.variantId == "variant-2" }.quantity)
        assertTrue(database.carts.none { it.id == "guest-cart" })

        val noGuest = CartRepository(FakeCartDatabase.seed().dataSource()).merge("user-1", "missing", "INR", "merge-empty")
        assertEquals(1, noGuest.items.size)

        val disappeared = FakeCartDatabase.seed().also {
            it.carts += StoredCart("disappeared", null, hash("disappeared"), "INR", expiresAt = now.minusSeconds(1))
            it.hideAfterAbandonId = "disappeared"
        }
        assertEquals(1, CartRepository(disappeared.dataSource()).abandonExpired(10, "abandon-missing"))
        assertEquals("ABANDONED", disappeared.carts.first { it.id == "disappeared" }.status)
    }

    @Test
    fun `outbox records are readable and empty publication is a no op`() {
        val database = FakeCartDatabase.seed()
        val repository = CartRepository(database.dataSource())
        repository.add(actor, "product-1", "variant-1", 1, price, "event-1", "corr")
        val events = repository.unpublished(10)
        assertTrue(events.isNotEmpty())
        assertEquals("CartItemAdded", events.last().eventType)
        repository.markPublished(emptyList(), now)
        repository.markPublished(listOf(events.last().id), now)
        assertTrue(database.outbox.all { it.published })
    }

    private fun assertError(code: ErrorCode, block: () -> Unit) {
        val error = assertFailsWith<ApiException> { block() }
        assertEquals(code, error.errorCode)
    }

    private class FakeCartDatabase {
        private val clock = Instant.parse("2026-08-20T00:00:00Z")
        val carts = mutableListOf<StoredCart>()
        val items = mutableListOf<StoredItem>()
        val idempotency = mutableListOf<StoredIdempotency>()
        val outbox = mutableListOf<StoredOutbox>()
        var duplicateCartInsert = false
        var cartInsertFailure: Throwable? = null
        var hideAfterAbandonId: String? = null

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "createArrayOf" -> null
                    "setAutoCommit", "commit", "rollback", "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val params = linkedMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setTimestamp", "setBoolean", "setArray" -> params[args!![0] as Int] = args[1]
                    "setNull" -> params[args!![0] as Int] = null
                    "executeQuery" -> resultSet(query(sql, params))
                    "executeUpdate" -> update(sql, params)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun query(sql: String, params: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.contains("FROM carts WHERE user_id") -> carts.filter { it.userId == params[1] && it.status == "ACTIVE" }.map(::cartRow)
            sql.contains("FROM carts WHERE guest_id_hash") -> carts.filter { it.guestHash == params[1] && it.status == "ACTIVE" }.map(::cartRow)
            sql.contains("FROM carts WHERE id") -> carts.filter { it.id == params[1] && it.id != hideAfterAbandonId }.map(::cartRow)
            sql.startsWith("SELECT * FROM cart_items WHERE cart_id=? AND variant_id") -> items.filter { it.cartId == params[1] && it.variantId == params[2] }.map(::itemRow)
            sql.startsWith("SELECT * FROM cart_items WHERE cart_id=? ORDER") -> items.filter { it.cartId == params[1] }.map(::itemRow)
            sql.startsWith("SELECT request_hash,response_json") -> idempotency.filter { it.cartId == params[1] && it.key == params[2] }.map { mapOf<Any, Any?>(1 to it.hash, 2 to it.response) }
            sql.startsWith("SELECT id FROM carts WHERE status='ACTIVE'") -> carts.filter { it.status == "ACTIVE" && it.expiresAt <= clock }.map { mapOf<Any, Any?>(1 to it.id) }
            sql.startsWith("SELECT id,aggregate_id,event_type") -> outbox.filter { !it.published }.map { mapOf<Any, Any?>(1 to it.id, 2 to it.aggregateId, 3 to it.eventType, 4 to 1, 5 to Timestamp.from(clock), 6 to it.correlation, 7 to it.payload) }
            else -> emptyList()
        }

        private fun update(sql: String, params: Map<Int, Any?>): Int {
            when {
                sql.startsWith("INSERT INTO carts") -> {
                    cartInsertFailure?.let { throw it }
                    val id = params[1].toString()
                    if (duplicateCartInsert && carts.none { it.userId == params[2] }) {
                        carts += StoredCart("existing", params[2]?.toString(), params[3]?.toString(), params[4].toString())
                        throw SQLException("duplicate", "23505")
                    }
                    carts += StoredCart(id, params[2]?.toString(), params[3]?.toString(), params[4].toString())
                }
                sql.startsWith("INSERT INTO cart_items") -> items += StoredItem(params[1].toString(), params[2].toString(), params[3].toString(), params[4].toString(), params[5] as Int, params[6] as Long, params[7].toString(), params[8].toString())
                sql.startsWith("UPDATE cart_items SET quantity=?,unit_price") -> items.firstOrNull { it.id == params[6] }?.apply { quantity = params[1] as Int; unitPrice = params[2] as Long; currency = params[3].toString(); priceVersion = params[4].toString() }
                sql.startsWith("UPDATE cart_items SET quantity=?,updated_at") -> items.firstOrNull { it.id == params[3] }?.quantity = params[1] as Int
                sql.startsWith("DELETE FROM cart_items WHERE cart_id=? AND variant_id") -> items.removeIf { it.cartId == params[1] && it.variantId == params[2] }
                sql.startsWith("DELETE FROM cart_items WHERE cart_id=?") -> items.removeIf { it.cartId == params[1] }
                sql.startsWith("UPDATE carts SET version") -> carts.firstOrNull { it.id == params[3] }?.version = params[1] as Long
                sql.startsWith("UPDATE carts SET status='ABANDONED'") -> carts.firstOrNull { it.id == params[1] }?.status = "ABANDONED"
                sql.startsWith("INSERT INTO cart_idempotency") -> idempotency += StoredIdempotency(params[1].toString(), params[2].toString(), params[3].toString(), params[4].toString())
                sql.startsWith("INSERT INTO cart_outbox_events") -> outbox += StoredOutbox(params[1].toString(), params[2].toString(), params[3].toString(), params[5].toString(), params[6].toString())
                sql.startsWith("DELETE FROM carts") -> carts.removeIf { it.id == params[1] }
                sql.startsWith("UPDATE cart_outbox_events") -> outbox.forEach { it.published = true }
            }
            return 1
        }

        private fun cartRow(cart: StoredCart) = mapOf<Any, Any?>("id" to cart.id, "user_id" to cart.userId, "currency" to cart.currency, "version" to cart.version)
        private fun itemRow(item: StoredItem) = mapOf<Any, Any?>("id" to item.id, "product_id" to item.productId, "variant_id" to item.variantId, "quantity" to item.quantity, "unit_price_minor" to item.unitPrice, "currency" to item.currency, "price_version" to item.priceVersion, "added_at" to Timestamp.from(clock), "updated_at" to Timestamp.from(clock))

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key)
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getLong" -> (value(key) as? Number)?.toLong() ?: 0L
                    "getTimestamp" -> value(key) as? Timestamp
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        companion object {
            fun seed() = FakeCartDatabase().also {
                it.carts += StoredCart("cart-1", "user-1", null, "INR")
                it.items += StoredItem("item-1", "cart-1", "product-1", "variant-1", 1, 1_000, "INR", "v1")
            }
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }

    private data class StoredCart(val id: String, val userId: String?, val guestHash: String?, val currency: String, var status: String = "ACTIVE", var version: Long = 1, val expiresAt: Instant = Instant.parse("2099-01-01T00:00:00Z"))
    private data class StoredItem(val id: String, val cartId: String, val productId: String, val variantId: String, var quantity: Int, var unitPrice: Long, var currency: String, var priceVersion: String)
    private data class StoredIdempotency(val cartId: String, val key: String, val hash: String, val response: String)
    private data class StoredOutbox(val id: String, val aggregateId: String, val eventType: String, val correlation: String, val payload: String, var published: Boolean = false)

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
