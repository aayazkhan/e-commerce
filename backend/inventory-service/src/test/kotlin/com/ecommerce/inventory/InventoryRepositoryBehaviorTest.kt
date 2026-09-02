package com.ecommerce.inventory

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryRepositoryBehaviorTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val actor = "actor-1"
    private val input = ReservationInput("reservation-1", actor, "cart-1", "order-1", listOf(ReservationItem("variant-1", "warehouse-1", 3)), 60)

    @Test
    fun `create and lookup inventory items preserve stock values`() {
        val database = FakeInventoryDatabase()
        val repository = InventoryRepository(database.dataSource())
        val item = repository.createItem(InventoryItemInput("", "WH1", "Main", "product-1", "variant-1", 10, 2), actor, "create")
        assertEquals(10, item.onHand)
        assertEquals(10, item.available)
        assertEquals(item, repository.findItem("variant-1", item.warehouseId))
        assertEquals(item, repository.findByVariant("variant-1").single())
        assertEquals(1, repository.unpublished(20).size)
    }

    @Test
    fun `reserve normalizes duplicate lines and replays idempotently`() {
        val database = FakeInventoryDatabase.seed()
        val repository = InventoryRepository(database.dataSource())
        val duplicated = input.copy(items = listOf(ReservationItem("variant-1", "warehouse-1", 2), ReservationItem("variant-1", "warehouse-1", 3)))
        val reservation = repository.reserve(duplicated, "reserve")
        assertEquals(5, reservation.items.single().quantity)
        assertEquals(5, database.item.available)
        assertEquals(reservation, repository.reserve(duplicated, "reserve"))

        val conflict = assertFailsWith<ApiException> { repository.reserve(duplicated.copy(ttlSeconds = 120), "reserve") }
        assertEquals(ErrorCode.CONFLICT, conflict.errorCode)
    }

    @Test
    fun `reservation transitions enforce ownership and state machine`() {
        val database = FakeInventoryDatabase.seed()
        val repository = InventoryRepository(database.dataSource())
        val reservation = repository.reserve(input, "reserve")
        assertError(ErrorCode.FORBIDDEN) { repository.release(reservation.id, "other-actor", "release") }
        assertEquals(ReservationStatus.RELEASED, repository.release(reservation.id, actor, "release").status)
        assertEquals(ReservationStatus.RELEASED, repository.release(reservation.id, actor, "release-again").status)
        assertError(ErrorCode.CONFLICT) { repository.commit(reservation.id, actor, "commit-after-release") }

        val committedDatabase = FakeInventoryDatabase.seed()
        val committedRepository = InventoryRepository(committedDatabase.dataSource())
        val committed = committedRepository.reserve(input, "reserve")
        assertEquals(ReservationStatus.COMMITTED, committedRepository.commit(committed.id, actor, "commit").status)
        assertEquals(7, committedDatabase.item.onHand)
        assertEquals(7, committedDatabase.item.available)
        assertEquals(ReservationStatus.COMMITTED, committedRepository.commit(committed.id, actor, "commit-again").status)
        assertError(ErrorCode.CONFLICT) { committedRepository.release(committed.id, actor, "release-after-commit") }
        assertError(ErrorCode.NOT_FOUND) { committedRepository.release("missing", actor, "missing") }
    }

    @Test
    fun `reservation handles a duplicate idempotency insert race without duplicating stock`() {
        val database = FakeInventoryDatabase.seed().also { it.duplicateIdempotencyInsert = true }
        val repository = InventoryRepository(database.dataSource())

        val reservation = repository.reserve(input, "race")

        assertEquals(ReservationStatus.ACTIVE, reservation.status)
        assertEquals(3, reservation.items.single().quantity)
        assertEquals(7, database.item.available)
        assertEquals(reservation.id, database.idempotency["reservation-1"]?.second)
    }

    @Test
    fun `reservation idempotency race rejects a conflicting request hash`() {
        val database = FakeInventoryDatabase.seed().also { it.duplicateIdempotencyInsert = true; it.conflictingIdempotencyRace = true }
        val repository = InventoryRepository(database.dataSource())

        val error = assertFailsWith<ApiException> { repository.reserve(input, "race") }

        assertEquals(ErrorCode.CONFLICT, error.errorCode)
    }

    @Test
    fun `adjustment preserves reserved invariant and movement history`() {
        val database = FakeInventoryDatabase.seed()
        val repository = InventoryRepository(database.dataSource())
        val reserved = repository.reserve(input, "reserve")
        val adjusted = repository.adjust("item-1", 2, AdjustmentType.RESTOCK, "restock", actor, "adjust")
        assertEquals(12, adjusted.onHand)
        assertEquals(9, adjusted.available)
        assertEquals(2, repository.movements("variant-1", 500).size)
        repository.release(reserved.id, actor, "release")

        assertError(ErrorCode.CONFLICT) { repository.adjust("item-1", -20, AdjustmentType.LOSS, "loss", actor, "bad") }
        assertError(ErrorCode.NOT_FOUND) { repository.adjust("missing", 1, AdjustmentType.RESTOCK, "reason", actor, "missing") }
        assertTrue(repository.unpublished(50).isNotEmpty())
    }

    @Test
    fun `expired reservations are released and publication can be acknowledged`() {
        val database = FakeInventoryDatabase.seed()
        val repository = InventoryRepository(database.dataSource())
        database.expired = true
        val reservation = repository.reserve(input, "reserve")
        database.reservations[reservation.id]!!.expiresAt = now.minusSeconds(1).toString()
        assertEquals(1, repository.expireBatch(2, "expire"))
        assertEquals(ReservationStatus.EXPIRED, repository.getReservation(reservation.id)!!.status)
        val events = repository.unpublished(100)
        repository.markPublished(events.map { it.id }, now)
        assertTrue(database.outbox.all { it.published })
    }

    @Test
    fun `low stock and out of stock notifications are raised and reset on recovery`() {
        val database = FakeInventoryDatabase.seed(threshold = 10)
        val repository = InventoryRepository(database.dataSource())
        val reservation = repository.reserve(input.copy(items = listOf(ReservationItem("variant-1", "warehouse-1", 10))), "reserve")

        assertTrue(database.outbox.any { it.type == "InventoryLowStock" })
        assertTrue(database.outbox.any { it.type == "InventoryOutOfStock" })
        assertTrue(database.item.lowNotified)
        assertTrue(database.item.outNotified)

        repository.release(reservation.id, actor, "release")
        assertTrue(database.updates.any { it.contains("out_of_stock_notified=FALSE") })
        assertFalse(database.item.outNotified)
        assertTrue(database.item.lowNotified)
        repository.adjust("item-1", 1, AdjustmentType.RESTOCK, "recovery", actor, "adjust")
        assertFalse(database.item.lowNotified)
    }

    @Test
    fun `zero low stock threshold never emits a low stock notification`() {
        val database = FakeInventoryDatabase.seed(threshold = 0)
        val repository = InventoryRepository(database.dataSource())

        repository.reserve(input.copy(items = listOf(ReservationItem("variant-1", "warehouse-1", 10))), "reserve")

        assertTrue(database.outbox.none { it.type == "InventoryLowStock" })
        assertTrue(database.outbox.any { it.type == "InventoryOutOfStock" })
        assertFalse(database.item.lowNotified)
    }

    @Test
    fun `repeated low and out of stock observations do not duplicate alerts`() {
        val lowDatabase = FakeInventoryDatabase.seed(threshold = 10)
        val lowRepository = InventoryRepository(lowDatabase.dataSource())
        val first = lowRepository.reserve(input.copy(items = listOf(ReservationItem("variant-1", "warehouse-1", 10))), "first")
        val firstAlertCount = lowDatabase.outbox.count { it.type == "InventoryLowStock" }
        lowRepository.release(first.id, actor, "release")
        lowRepository.reserve(input.copy(reservationKey = "second", items = listOf(ReservationItem("variant-1", "warehouse-1", 1))), "second")

        assertEquals(firstAlertCount, lowDatabase.outbox.count { it.type == "InventoryLowStock" })

        val outDatabase = FakeInventoryDatabase.seed(threshold = 10)
        val outRepository = InventoryRepository(outDatabase.dataSource())
        val committed = outRepository.reserve(input.copy(items = listOf(ReservationItem("variant-1", "warehouse-1", 10))), "commit")
        val firstOutAlertCount = outDatabase.outbox.count { it.type == "InventoryOutOfStock" }
        outRepository.commit(committed.id, actor, "commit")

        assertEquals(firstOutAlertCount, outDatabase.outbox.count { it.type == "InventoryOutOfStock" })
    }

    @Test
    fun `missing notification flag rows fail closed and still emit stock alerts`() {
        val database = FakeInventoryDatabase.seed(threshold = 10).also { it.notificationRowsMissing = true }
        val repository = InventoryRepository(database.dataSource())

        repository.reserve(input.copy(items = listOf(ReservationItem("variant-1", "warehouse-1", 10))), "reserve")

        assertTrue(database.outbox.any { it.type == "InventoryLowStock" })
        assertTrue(database.outbox.any { it.type == "InventoryOutOfStock" })
    }

    @Test
    fun `inventory reads cover missing rows empty histories and existing warehouse creation`() {
        val emptyDatabase = FakeInventoryDatabase()
        val emptyRepository = InventoryRepository(emptyDatabase.dataSource())
        assertNull(emptyRepository.findItem("missing", "warehouse-1"))
        assertTrue(emptyRepository.findByVariant("missing").isEmpty())
        assertTrue(emptyRepository.movements("missing", 0).isEmpty())
        assertEquals(0, emptyRepository.expireBatch(1, "expire"))

        val database = FakeInventoryDatabase()
        val repository = InventoryRepository(database.dataSource())
        val created = repository.createItem(InventoryItemInput("warehouse-1", null, null, "product-1", "variant-1", 4, 0), actor, "create")
        assertEquals("warehouse-1", created.warehouseId)
        assertEquals(4, created.available)

        val defaults = FakeInventoryDatabase()
        val defaultWarehouse = InventoryRepository(defaults.dataSource()).createItem(InventoryItemInput("", null, null, "product-2", "variant-2", 1, 0), actor, "create")
        assertEquals(defaultWarehouse.warehouseId, defaults.item.warehouseId)
    }

    @Test
    fun `inventory create maps duplicate and unexpected persistence failures`() {
        val duplicate = FakeInventoryDatabase().also { it.itemInsertFailure = SQLException("duplicate", "23505") }
        assertError(ErrorCode.CONFLICT) {
            InventoryRepository(duplicate.dataSource()).createItem(InventoryItemInput("warehouse-1", "WH1", "Main", "product-1", "variant-1", 1, 0), actor, "create")
        }

        val unavailable = FakeInventoryDatabase().also { it.itemInsertFailure = SQLException("unavailable", "08001") }
        assertFailsWith<SQLException> {
            InventoryRepository(unavailable.dataSource()).createItem(InventoryItemInput("warehouse-1", "WH1", "Main", "product-1", "variant-1", 1, 0), actor, "create")
        }

        val unexpected = FakeInventoryDatabase().also { it.itemInsertFailure = IllegalStateException("wrapped persistence failure") }
        val unexpectedFailure = assertFailsWith<IllegalStateException> {
            InventoryRepository(unexpected.dataSource()).createItem(InventoryItemInput("warehouse-1", "WH1", "Main", "product-1", "variant-1", 1, 0), actor, "create")
        }
        assertEquals("wrapped persistence failure", unexpectedFailure.message)
    }

    @Test
    fun `reservation rejects exhausted stock and failed completion updates`() {
        val exhausted = FakeInventoryDatabase.seed()
        assertError(ErrorCode.CONFLICT) {
            InventoryRepository(exhausted.dataSource()).reserve(input.copy(items = listOf(ReservationItem("variant-1", "warehouse-1", 11))), "reserve")
        }

        val database = FakeInventoryDatabase.seed()
        val repository = InventoryRepository(database.dataSource())
        val reservation = repository.reserve(input, "reserve")
        database.failTransitionUpdate = true
        assertError(ErrorCode.CONFLICT) { repository.commit(reservation.id, actor, "commit") }
    }

    @Test
    fun `expired reservation transition is idempotent`() {
        val database = FakeInventoryDatabase.seed()
        val repository = InventoryRepository(database.dataSource())
        val reservation = repository.reserve(input, "expire")

        assertEquals(ReservationStatus.EXPIRED, repository.release(reservation.id, actor, "expire", expired = true).status)
        assertEquals(ReservationStatus.EXPIRED, repository.release(reservation.id, actor, "expire-again", expired = true).status)
        assertError(ErrorCode.CONFLICT) { repository.commit(reservation.id, actor, "commit-after-expiry") }
    }

    @Test
    fun `reservation duplicate race without a readable prior row preserves the database error`() {
        val database = FakeInventoryDatabase.seed().also {
            it.duplicateIdempotencyInsert = true
            it.idempotencyRaceWithoutPrior = true
        }

        val failure = assertFailsWith<SQLException> {
            InventoryRepository(database.dataSource()).reserve(input, "race-without-prior")
        }

        assertEquals("23505", failure.sqlState)
    }

    @Test
    fun `low stock evaluation fails closed when the locked inventory row disappeared`() {
        val database = FakeInventoryDatabase.seed(threshold = 10).also { it.lockItemMissing = true }
        val reservation = InventoryRepository(database.dataSource()).reserve(input, "missing-lock")

        assertEquals(ReservationStatus.ACTIVE, reservation.status)
        assertTrue(database.outbox.none { it.type == "InventoryLowStock" })
    }

    private fun assertError(code: ErrorCode, block: () -> Unit) {
        val error = assertFailsWith<ApiException> { block() }
        assertEquals(code, error.errorCode)
    }

    private class FakeInventoryDatabase {
        private val clock = Instant.parse("2026-08-20T00:00:00Z")
        lateinit var item: StoredItem
        val reservations = linkedMapOf<String, StoredReservation>()
        val idempotency = linkedMapOf<String, Pair<String, String>>()
        val outbox = mutableListOf<StoredOutbox>()
        val updates = mutableListOf<String>()
        val movements = mutableListOf<StoredMovement>()
        var expired = false
        var itemInsertFailure: Throwable? = null
        var failTransitionUpdate = false
        var duplicateIdempotencyInsert = false
        var conflictingIdempotencyRace = false
        var idempotencyRaceWithoutPrior = false
        var notificationRowsMissing = false
        var lockItemMissing = false

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
            sql.contains("FROM inventory_items WHERE variant_id=? AND warehouse_id") -> if (::item.isInitialized && item.variantId == params[1] && item.warehouseId == params[2]) listOf(itemRow()) else emptyList()
            sql.contains("FROM inventory_items WHERE variant_id=? ORDER") -> if (::item.isInitialized && item.variantId == params[1]) listOf(itemRow()) else emptyList()
            sql.contains("FROM inventory_items WHERE id=?") && !sql.startsWith("SELECT low_stock_notified") && !sql.startsWith("SELECT out_of_stock_notified") -> if (!lockItemMissing && ::item.isInitialized && item.id == params[1]) listOf(itemRow()) else emptyList()
            sql.startsWith("SELECT request_hash,reservation_id") -> idempotency[params[1].toString()]?.let { listOf(mapOf<Any, Any?>(1 to it.first, 2 to it.second)) } ?: emptyList()
            sql.startsWith("SELECT * FROM inventory_reservations WHERE id") -> reservations[params[1].toString()]?.let { listOf(reservationRow(it)) } ?: emptyList()
            sql.startsWith("SELECT variant_id,warehouse_id,quantity") -> reservations[params[1].toString()]?.items?.map { mapOf<Any, Any?>(1 to it.variantId, 2 to it.warehouseId, 3 to it.quantity) } ?: emptyList()
            sql.startsWith("SELECT id FROM inventory_reservations") -> if (expired) reservations.values.filter { it.status == ReservationStatus.ACTIVE }.map { mapOf<Any, Any?>(1 to it.id) } else emptyList()
            sql.startsWith("SELECT low_stock_notified") -> if (notificationRowsMissing) emptyList() else listOf(mapOf<Any, Any?>(1 to item.lowNotified))
            sql.startsWith("SELECT out_of_stock_notified") -> if (notificationRowsMissing) emptyList() else listOf(mapOf<Any, Any?>(1 to item.outNotified))
            sql.startsWith("SELECT m.id") -> movements.map { mapOf<Any, Any?>(1 to it.id, 2 to it.type, 3 to it.delta, 4 to it.onHand, 5 to it.reserved, 6 to it.available, 7 to it.reference, 8 to it.actor, 9 to Timestamp.from(clock)) }
            sql.startsWith("SELECT id,aggregate_type") -> outbox.filter { !it.published }.map { mapOf<Any, Any?>(1 to it.id, 2 to it.aggregateType, 3 to it.aggregateId, 4 to it.type, 5 to 1, 6 to Timestamp.from(clock), 7 to it.correlation, 8 to "{}") }
            else -> emptyList()
        }

        private fun update(sql: String, params: Map<Int, Any?>): Int {
            updates += sql
            when {
                sql.startsWith("INSERT INTO inventory_warehouses") -> Unit
                sql.startsWith("INSERT INTO inventory_items") -> { itemInsertFailure?.let { throw it }; item = StoredItem(params[1].toString(), params[2].toString(), params[3].toString(), params[4].toString(), params[5] as Long, 0, params[6] as Long, params[7] as Long) }
                sql.startsWith("INSERT INTO inventory_reservations") -> reservations[params[1].toString()] = StoredReservation(params[1].toString(), params[2].toString(), params[3].toString(), ReservationStatus.ACTIVE, (params[6] as Timestamp).toInstant().toString(), mutableListOf())
                sql.startsWith("UPDATE inventory_items SET reserved=reserved+") -> { if (failTransitionUpdate) return 0; val quantity = params[1] as Long; if (item.available < quantity) return 0; item.reserved += quantity; item.available -= quantity }
                sql.startsWith("UPDATE inventory_items SET on_hand=on_hand") -> { if (failTransitionUpdate) return 0; val quantity = params[1] as Long; if (item.reserved < quantity || item.onHand < quantity) return 0; item.onHand -= quantity; item.reserved -= quantity; item.available = item.onHand - item.reserved }
                sql.startsWith("UPDATE inventory_items SET reserved=reserved-") -> { val quantity = params[1] as Long; if (item.reserved < quantity) return 0; item.reserved -= quantity; item.available += quantity }
                sql.startsWith("INSERT INTO inventory_reservation_items") -> reservations[params[1].toString()]?.items?.add(ReservationItem(params[3].toString(), params[4].toString(), params[5] as Long))
                sql.startsWith("INSERT INTO inventory_idempotency") -> {
                    if (duplicateIdempotencyInsert) {
                        if (!idempotencyRaceWithoutPrior) idempotency[params[1].toString()] = (if (conflictingIdempotencyRace) "different-hash" else params[2].toString()) to params[3].toString()
                        throw SQLException("duplicate", "23505")
                    }
                    idempotency[params[1].toString()] = params[2].toString() to params[3].toString()
                }
                sql.startsWith("UPDATE inventory_reservations SET status") -> reservations[params[3].toString()]?.status = ReservationStatus.valueOf(params[1].toString())
                sql.startsWith("UPDATE inventory_items SET on_hand=?") -> { item.onHand = params[1] as Long; item.available = params[2] as Long }
                sql.startsWith("UPDATE inventory_items SET low_stock_notified") -> item.lowNotified = sql.uppercase().contains("=TRUE")
                sql.startsWith("UPDATE inventory_items SET out_of_stock_notified") -> item.outNotified = sql.uppercase().contains("=TRUE")
                sql.startsWith("INSERT INTO inventory_movements") -> movements += StoredMovement(params[1].toString(), params[3].toString(), params[4] as Long, params[5] as Long, params[6] as Long, params[7] as Long, params[8]?.toString(), params[9].toString())
                sql.startsWith("INSERT INTO inventory_outbox_events") -> outbox += StoredOutbox(params[1].toString(), params[2].toString(), params[3].toString(), params[4].toString())
                sql.startsWith("UPDATE inventory_outbox_events") -> outbox.forEach { it.published = true }
            }
            return 1
        }

        private fun itemRow() = mapOf<Any, Any?>("id" to item.id, "warehouse_id" to item.warehouseId, "product_id" to item.productId, "variant_id" to item.variantId, "on_hand" to item.onHand, "reserved" to item.reserved, "available" to item.available, "low_stock_threshold" to item.threshold, "version" to item.version, "low_stock_notified" to item.lowNotified, "out_of_stock_notified" to item.outNotified, "updated_at" to Timestamp.from(clock))
        private fun reservationRow(reservation: StoredReservation) = mapOf<Any, Any?>("id" to reservation.id, "reservation_key" to reservation.key, "actor_id" to reservation.actor, "cart_id" to null, "order_id" to null, "status" to reservation.status.name, "expires_at" to Timestamp.from(Instant.parse(reservation.expiresAt)), "created_at" to Timestamp.from(clock), "updated_at" to Timestamp.from(clock))

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            var wasNull = false
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key).also { wasNull = it == null }
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getLong" -> (value(key) as? Number)?.toLong() ?: 0L
                    "getBoolean" -> value(key) as? Boolean ?: false
                    "getTimestamp" -> value(key) as? Timestamp
                    "wasNull" -> wasNull
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        companion object {
            fun seed(threshold: Long = 2) = FakeInventoryDatabase().also { it.item = StoredItem("item-1", "warehouse-1", "product-1", "variant-1", 10, 0, 10, threshold) }
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }

    private data class StoredItem(val id: String, val warehouseId: String, val productId: String, val variantId: String, var onHand: Long, var reserved: Long, var available: Long, val threshold: Long, var version: Long = 1, var lowNotified: Boolean = false, var outNotified: Boolean = false)
    private data class StoredReservation(val id: String, val key: String, val actor: String, var status: ReservationStatus, var expiresAt: String, val items: MutableList<ReservationItem>)
    private data class StoredMovement(val id: String, val type: String, val delta: Long, val onHand: Long, val reserved: Long, val available: Long, val reference: String?, val actor: String)
    private data class StoredOutbox(val id: String, val aggregateType: String, val aggregateId: String, val type: String, var published: Boolean = false) {
        val correlation: String get() = aggregateId
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
