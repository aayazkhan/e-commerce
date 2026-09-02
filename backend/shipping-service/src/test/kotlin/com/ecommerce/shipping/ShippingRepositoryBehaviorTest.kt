package com.ecommerce.shipping

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Array
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShippingRepositoryBehaviorTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val address = ShippingAddress("Customer", "+919999999999", "1 Main Road", city = "Pune", state = "MH", postalCode = "411001", country = "IN")
    private val items = listOf(ShipmentItem("variant-1", 1))
    private val provider = object : ShippingProvider {
        override val name = "fake"
        override fun quote(request: ShippingQuoteRequest) = ShippingQuote("quote-1", request.method, 149, request.currency, "2026-08-21T00:00:00Z")
        override fun create(request: ShipmentCreateRequest) = ProviderShipment("provider-1", ShipmentStatus.LABEL_CREATED, "TRACK-1", "Carrier")
        override fun track(providerShipmentId: String) = ProviderShipment(providerShipmentId, ShipmentStatus.PICKED_UP, "TRACK-2", "Carrier")
        override fun cancel(providerShipmentId: String) = ProviderShipment(providerShipmentId, ShipmentStatus.CANCELLED, null, "Carrier")
        override fun verifyWebhook(body: String, signature: String?) = true
    }

    @Test
    fun `quote create lookup and outbox publication preserve shipping state`() {
        val database = MemoryShippingDatabase()
        val repository = ShippingRepository(database.dataSource(), provider)
        val quote = repository.quote("user-1", ShippingQuoteRequest(address, items, ShippingMethod.EXPRESS, "INR"))
        assertEquals(149, quote.amountMinor)

        val request = createRequest()
        val created = repository.create(request, "ship-key", "corr")
        assertEquals(ShipmentStatus.LABEL_CREATED, created.status)
        assertEquals(created, repository.getOwned("user-1", created.id))
        assertNull(repository.getOwned("other-user", created.id))
        assertEquals(created, repository.getInternal(created.id))
        assertNull(repository.getInternal("missing"))

        val events = repository.unpublished(100)
        assertTrue(events.isNotEmpty())
        repository.markPublished(events.map { it.id }, now)
        repository.markPublished(emptyList(), now)
        assertTrue(repository.unpublished(100).isEmpty())
    }

    @Test
    fun `create is idempotent and rejects a changed request under the same key`() {
        val database = MemoryShippingDatabase()
        val repository = ShippingRepository(database.dataSource(), provider)
        val request = createRequest()
        val first = repository.create(request, "ship-key", "corr")
        assertEquals(first, repository.create(request, "ship-key", "corr"))

        val conflict = assertFailsWith<ApiException> {
            repository.create(request.copy(amountMinor = 101), "ship-key", "corr")
        }
        assertEquals(ErrorCode.CONFLICT, conflict.errorCode)
    }

    @Test
    fun `tracking persists the first webhook and ignores duplicates`() {
        val database = MemoryShippingDatabase()
        val repository = ShippingRepository(database.dataSource(), provider)
        val created = repository.create(createRequest(), "tracking-key", "corr")
        val webhook = TrackingWebhook("event-1", "provider-1", ShipmentStatus.PICKED_UP, "TRACK-2", mapOf("carrier" to "Carrier"))

        val tracked = repository.tracking(webhook, "corr")
        assertEquals(ShipmentStatus.PICKED_UP, tracked.status)
        assertEquals("TRACK-2", tracked.trackingNumber)
        assertEquals(tracked, repository.tracking(webhook, "corr"))
        assertEquals(1, database.trackingEvents.size)
        assertTrue(repository.unpublished(100).isNotEmpty())
        assertEquals(created.id, tracked.id)
    }

    @Test
    fun `tracking maps missing shipment and invalid state to domain errors`() {
        val database = MemoryShippingDatabase()
        val repository = ShippingRepository(database.dataSource(), provider)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> {
            repository.tracking(TrackingWebhook("missing", "provider-missing", ShipmentStatus.PICKED_UP, null), "corr")
        }.errorCode)
        repository.create(createRequest(), "invalid-state", "corr")
        val error = assertFailsWith<ApiException> {
            repository.tracking(TrackingWebhook("invalid", "provider-1", ShipmentStatus.DELIVERED, null), "corr")
        }
        assertEquals(ErrorCode.CONFLICT, error.errorCode)
    }

    private fun createRequest() = ShipmentCreateRequest("order-1", "user-1", address, items, ShippingMethod.STANDARD, 100, "INR")
}

private class MemoryShippingDatabase {
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    data class Row(var response: ShipmentResponse, val userId: String, val key: String, val hash: String)
    data class Outbox(val id: String, val aggregateId: String, val type: String, var published: Boolean = false)

    val shipments = linkedMapOf<String, Row>()
    val trackingEvents = linkedSetOf<String>()
    val outbox = linkedMapOf<String, Outbox>()

    fun dataSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
        if (method.name == "getConnection") connection() else defaultValue(method.returnType)
    }) as DataSource

    private fun connection(): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
        when (method.name) {
            "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
            "createArrayOf" -> sqlArray(args?.getOrNull(1))
            "setAutoCommit", "commit", "rollback", "close" -> null
            else -> defaultValue(method.returnType)
        }
    }) as Connection

    private fun statement(raw: String): PreparedStatement {
        val sql = raw.replace(Regex("\\s+"), " ").trim().uppercase()
        val params = mutableMapOf<Int, Any?>()
        return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
            when {
                method.name.startsWith("set") && args?.firstOrNull() is Int -> { params[args[0] as Int] = args.getOrNull(1); null }
                method.name == "executeQuery" -> resultSet(query(sql, params))
                method.name == "executeUpdate" -> update(sql, params)
                method.name == "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as PreparedStatement
    }

    private fun query(sql: String, params: Map<Int, Any?>): List<Map<Any, Any?>> = when {
        sql.startsWith("SELECT REQUEST_HASH,ID FROM SHIPMENTS") -> shipments.values.filter { it.userId == params[1] && it.key == params[2] }.map { mapOf<Any, Any?>(1 to it.hash, 2 to it.response.id) }
        sql.startsWith("SELECT ID FROM SHIPMENT_TRACKING_EVENTS") -> trackingEvents.filter { it == params[1].toString() }.map { mapOf<Any, Any?>(1 to "track-$it") }
        sql.startsWith("SELECT * FROM SHIPMENTS WHERE") -> shipments.values.filter {
            val byProvider = sql.contains("PROVIDER_SHIPMENT_ID")
            val matchesId = if (byProvider) it.response.providerShipmentId == params[1] else it.response.id == params[1]
            val matchesUser = !sql.contains("USER_ID=?") || it.userId == params[2]
            matchesId && matchesUser
        }.map { responseMap(it.response) }
        sql.startsWith("SELECT ID,AGGREGATE_ID,EVENT_TYPE") -> outbox.values.filterNot { it.published }.map { mapOf<Any, Any?>(1 to it.id, 2 to it.aggregateId, 3 to it.type, 4 to 1, 5 to Timestamp.from(now), 6 to "corr", 7 to "{}") }
        else -> emptyList()
    }

    private fun update(sql: String, params: Map<Int, Any?>): Int = when {
        sql.startsWith("INSERT INTO SHIPMENTS") -> {
            val id = params[1].toString()
            val created = params[11] as Timestamp
            val response = ShipmentResponse(id, params[2].toString(), params[3].toString(), params[4].toString(), null, ShipmentStatus.valueOf(params[5].toString()), ShippingMethod.valueOf(params[6].toString()), null, null, (params[7] as Number).toLong(), params[8].toString(), created.toInstant().toString(), created.toInstant().toString())
            shipments[id] = Row(response, params[3].toString(), params[9].toString(), params[10].toString())
            1
        }
        sql.startsWith("UPDATE SHIPMENTS SET STATUS") -> {
            val row = shipments[params[6].toString()] ?: return 0
            row.response = row.response.copy(status = ShipmentStatus.valueOf(params[1].toString()), providerShipmentId = params[2]?.toString(), trackingNumber = params[3]?.toString(), carrier = params[4]?.toString())
            1
        }
        sql.startsWith("INSERT INTO SHIPMENT_TRACKING_EVENTS") -> { trackingEvents += params[4].toString(); 1 }
        sql.startsWith("INSERT INTO SHIPPING_OUTBOX_EVENTS") -> { val id = params[1].toString(); outbox[id] = Outbox(id, params[2].toString(), params[3].toString()); 1 }
        sql.startsWith("UPDATE SHIPPING_OUTBOX_EVENTS") -> { val ids = (params[2] as Array).getArray() as kotlin.Array<*>; outbox.values.filter { ids.any { id -> id.toString() == it.id } }.forEach { it.published = true }; 1 }
        else -> 1
    }

    private fun responseMap(response: ShipmentResponse) = mapOf<Any, Any?>(
        "id" to response.id, "order_id" to response.orderId, "user_id" to response.userId, "provider" to response.provider,
        "provider_shipment_id" to response.providerShipmentId, "status" to response.status.name, "method" to response.method.name,
        "tracking_number" to response.trackingNumber, "carrier" to response.carrier, "amount_minor" to response.amountMinor,
        "currency" to response.currency, "created_at" to Timestamp.from(Instant.parse(response.createdAt)), "updated_at" to Timestamp.from(Instant.parse(response.updatedAt)),
    )

    private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
        var index = -1
        fun value(key: Any): Any? = rows.getOrNull(index)?.let { row -> key.toString().toIntOrNull()?.let { row.keys.elementAtOrNull(it - 1) }?.let(row::get) ?: row[key] }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
            when (method.name) {
                "next" -> if (index + 1 < rows.size) { index++; true } else false
                "getString" -> value(args?.firstOrNull() ?: "")?.toString()
                "getInt" -> (value(args?.firstOrNull() ?: "") as? Number)?.toInt() ?: 0
                "getLong" -> (value(args?.firstOrNull() ?: "") as? Number)?.toLong() ?: 0L
                "getTimestamp" -> value(args?.firstOrNull() ?: "") as? Timestamp
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as ResultSet
    }

    private fun sqlArray(values: Any?): Array = Proxy.newProxyInstance(Array::class.java.classLoader, arrayOf(Array::class.java), InvocationHandler { _, method, _ -> if (method.name == "getArray") values else defaultValue(method.returnType) }) as Array
    private fun defaultValue(type: Class<*>): Any? = when (type) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
}
