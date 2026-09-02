package com.ecommerce.checkout

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckoutRepositoryBehaviorTest {
    private val request = CheckoutRequest("cart-1", "shipping-1", paymentMethodToken = "pm-1")
    private val totals = CheckoutTotals(1000, 0, 100, 50, 180, 1130, "INR")

    @Test
    fun `start persists saga and reuses identical idempotency request`() {
        val database = FakeCheckoutDatabase()
        val repository = CheckoutRepository(database.dataSource())

        val first = repository.start("user-1", request, "key-1")
        val replay = repository.start("user-1", request, "key-1")

        assertEquals(first, replay)
        assertEquals(CheckoutStatus.CREATED, first.status)
        assertEquals(CheckoutStep.VALIDATE_CART, first.currentStep)
        assertEquals(1, database.sagas.size)
        assertEquals(1, database.idempotency.size)
    }

    @Test
    fun `idempotency key cannot be reused for a different request`() {
        val repository = CheckoutRepository(FakeCheckoutDatabase().dataSource())
        repository.start("user-1", request, "key-1")

        val error = assertFailsWith<ApiException> {
            repository.start("user-1", request.copy(paymentMethodToken = "different"), "key-1")
        }

        assertEquals(ErrorCode.CONFLICT, error.errorCode)
        assertEquals(409, error.statusCode)
    }

    @Test
    fun `ownership reads and checkpoints preserve saga compensation fields`() {
        val database = FakeCheckoutDatabase()
        val repository = CheckoutRepository(database.dataSource())
        val started = repository.start("user-1", request, "key-1")

        assertNotNull(repository.get(started.checkoutId))
        assertNull(repository.get("missing-checkout"))
        assertNotNull(repository.getOwned("user-1", started.checkoutId))
        assertNull(repository.getOwned("other-user", started.checkoutId))

        repository.checkpoint(started.checkoutId, CheckoutStatus.PAYMENT_PROCESSING, CheckoutStep.CREATE_PAYMENT, "reservation-1", "order-1", "redemption-1", "payment-1", "PROCESSING", "secret", "shipment-1", totals)
        val checkpoint = assertNotNull(repository.get(started.checkoutId))
        assertEquals(CheckoutStatus.PAYMENT_PROCESSING, checkpoint.status)
        assertEquals(CheckoutStep.CREATE_PAYMENT, checkpoint.currentStep)
        assertEquals("reservation-1", checkpoint.reservationId)
        assertEquals("order-1", checkpoint.orderId)
        assertEquals("redemption-1", checkpoint.promotionRedemptionId)
        assertEquals("payment-1", checkpoint.payment?.id)
        assertEquals("secret", checkpoint.payment?.clientSecret)
        assertEquals(totals, checkpoint.totals)

        repository.fail(started.checkoutId, "payment failed".repeat(600), recoverable = true)
        val recoverable = assertNotNull(repository.get(started.checkoutId))
        assertEquals(CheckoutStatus.RECOVERABLE, recoverable.status)
        assertEquals(CheckoutStep.COMPLETE, recoverable.currentStep)
        assertEquals(1000, recoverable.error?.length)

        repository.fail(started.checkoutId, "terminal failure", recoverable = false)
        assertEquals(CheckoutStatus.FAILED, assertNotNull(repository.get(started.checkoutId)).status)
    }

    @Test
    fun `outbox records can be listed and acknowledged including empty acknowledgement`() {
        val database = FakeCheckoutDatabase()
        val repository = CheckoutRepository(database.dataSource())
        val started = repository.start("user-1", request, "key-1")

        val events = repository.unpublished(10)
        assertEquals(1, events.size)
        assertEquals(started.checkoutId, events.single().aggregateId)
        repository.markPublished(emptyList(), Instant.parse("2026-08-20T00:00:00Z"))
        repository.markPublished(events.map { it.id }, Instant.parse("2026-08-20T00:00:00Z"))
        assertTrue(database.outbox.single().published)
        assertTrue(repository.unpublished(10).isEmpty())
    }

    private class FakeCheckoutDatabase {
        val sagas = linkedMapOf<String, StoredSaga>()
        val idempotency = linkedMapOf<String, StoredIdempotency>()
        val outbox = mutableListOf<StoredOutbox>()
        private val now = Instant.parse("2026-08-20T00:00:00Z")

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
                    "setString", "setInt", "setLong", "setTimestamp", "setObject", "setArray" -> params[args!![0] as Int] = args[1]
                    "setNull" -> params[args!![0] as Int] = null
                    "executeQuery" -> resultSet(query(sql, params))
                    "executeUpdate" -> update(sql, params)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun update(sql: String, params: Map<Int, Any?>): Int {
            when {
                sql.startsWith("INSERT INTO checkout_sagas") -> {
                    val id = params[1].toString()
                    sagas[id] = StoredSaga(id, params[2].toString(), params[6].toString(), params[7].toString(), params[3].toString(), now.toString(), now.toString())
                }
                sql.startsWith("INSERT INTO checkout_idempotency") -> idempotency[params[3].toString()] = StoredIdempotency(params[2].toString(), params[4].toString(), params[5].toString())
                sql.startsWith("UPDATE checkout_sagas") -> {
                    val saga = sagas[params[18].toString()]!!
                    saga.status = params[1].toString(); saga.step = params[2].toString(); saga.attempt++; saga.version++
                    params[3]?.let { saga.reservationId = it.toString() }; params[4]?.let { saga.orderId = it.toString() }; params[5]?.let { saga.promotionId = it.toString() }
                    params[6]?.let { saga.paymentId = it.toString() }; params[7]?.let { saga.paymentStatus = it.toString() }; params[8]?.let { saga.paymentSecret = it.toString() }; params[9]?.let { saga.shipmentId = it.toString() }
                    params[10]?.let { saga.subtotal = (it as Number).toLong() }; params[11]?.let { saga.promotionDiscount = (it as Number).toLong() }; params[12]?.let { saga.shipping = (it as Number).toLong() }; params[13]?.let { saga.tax = (it as Number).toLong() }; params[14]?.let { saga.total = (it as Number).toLong() }; params[15]?.let { saga.currency = it.toString() }; saga.error = params[16]?.toString()
                }
                sql.startsWith("UPDATE checkout_outbox_events") -> outbox.forEach { it.published = true }
            }
            if (sql.startsWith("INSERT INTO checkout_sagas")) outbox += StoredOutbox("event-${outbox.size + 1}", sagas.keys.last(), "CheckoutStarted")
            return 1
        }

        private fun query(sql: String, params: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.startsWith("SELECT request_hash") -> idempotency[params[2].toString()]?.let { listOf(mapOf<Any, Any?>(1 to it.hash, 2 to it.checkoutId)) } ?: emptyList()
            sql.startsWith("SELECT id FROM checkout_sagas") -> sagas[params[1].toString()]?.takeIf { it.userId == params[2].toString() }?.let { listOf(mapOf<Any, Any?>(1 to it.id)) } ?: emptyList()
            sql.startsWith("SELECT * FROM checkout_sagas") -> sagas[params[1].toString()]?.let { listOf(row(it)) } ?: emptyList()
            sql.startsWith("SELECT id,aggregate_id") -> outbox.filter { !it.published }.map { mapOf<Any, Any?>(1 to it.id, 2 to it.aggregateId, 3 to it.type, 4 to 1, 5 to Timestamp.from(now), 6 to it.aggregateId, 7 to "{}") }
            else -> emptyList()
        }

        private fun row(saga: StoredSaga) = mapOf<Any, Any?>("id" to saga.id, "status" to saga.status, "current_step" to saga.step, "order_id" to saga.orderId, "reservation_id" to saga.reservationId, "promotion_redemption_id" to saga.promotionId, "payment_id" to saga.paymentId, "payment_status" to saga.paymentStatus, "payment_client_secret" to saga.paymentSecret, "subtotal_minor" to saga.subtotal, "promotion_discount_minor" to saga.promotionDiscount, "shipping_minor" to saga.shipping, "tax_minor" to saga.tax, "total_minor" to saga.total, "currency" to saga.currency, "last_error" to saga.error, "created_at" to Timestamp.from(Instant.parse(saga.createdAt)), "updated_at" to Timestamp.from(Instant.parse(saga.updatedAt)))

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
                    "getObject" -> value(key)
                    "getTimestamp" -> value(key) as? Timestamp
                    "wasNull" -> wasNull
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as ResultSet
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }

    private data class StoredIdempotency(val userId: String, val hash: String, val checkoutId: String)
    private data class StoredSaga(val id: String, val userId: String, var status: String, var step: String, val request: String, val createdAt: String, var updatedAt: String, var reservationId: String? = null, var orderId: String? = null, var promotionId: String? = null, var paymentId: String? = null, var paymentStatus: String? = null, var paymentSecret: String? = null, var shipmentId: String? = null, var subtotal: Long? = null, var promotionDiscount: Long? = null, var shipping: Long? = null, var tax: Long? = null, var total: Long? = null, var currency: String? = null, var error: String? = null, var attempt: Int = 0, var version: Int = 1)
    private data class StoredOutbox(val id: String, val aggregateId: String, val type: String, var published: Boolean = false)
}
