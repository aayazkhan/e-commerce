package com.ecommerce.order

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Array
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class OrderRepositoryPersistenceTest {
    private val now = Instant.parse("2020-01-01T00:00:00Z")

    @Test
    fun `create persists snapshots supports ownership and returns the idempotent result`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())
        val request = request(initialStatus = OrderStatus.INVENTORY_RESERVED, promotion = PromotionSnapshot("promo-1", "SAVE", 100, mapOf("variant-1" to 100)))

        val created = repository.create("user-1", request, "order-key", "checkout", "corr")
        assertEquals(OrderStatus.INVENTORY_RESERVED, created.status)
        assertEquals(1, created.items.size)
        assertEquals("variant-1", created.items.single().variantId)
        assertEquals("address-1", created.shippingAddress.addressId)
        assertEquals("promo-1", created.promotion?.promotionId)
        assertEquals(created, repository.getOwned("user-1", created.id))
        assertNull(repository.getOwned("user-2", created.id))
        assertEquals(created, repository.create("user-1", request, "order-key", "checkout", "corr"))

        val conflict = assertFailsWith<ApiException> {
            repository.create("user-1", request.copy(shippingMinor = 101, totalMinor = 1_181), "order-key", "checkout", "corr")
        }
        assertEquals(ErrorCode.CONFLICT, conflict.errorCode)
        assertEquals(2, repository.unpublished(20).size)
    }

    @Test
    fun `transition and cancellation enforce ownership state machine and idempotency`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())
        val created = repository.create("user-1", request(initialStatus = OrderStatus.CREATED), "created", "actor", "corr")
        assertEquals(OrderStatus.VALIDATING, repository.transition(created.id, "user-1", OrderStatus.VALIDATING, "actor", "validate", "corr").status)
        assertEquals(OrderStatus.VALIDATING, repository.transition(created.id, "user-1", OrderStatus.VALIDATING, "actor", null, "corr").status)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> {
            repository.transition(created.id, "user-2", OrderStatus.INVENTORY_RESERVED, "actor", null, "corr")
        }.errorCode)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.transition(created.id, "user-1", OrderStatus.PAID, "actor", null, "corr")
        }.errorCode)
        assertEquals(OrderStatus.CANCELLED, repository.cancel(created.id, "user-1", "changed mind", "user-1", "corr").status)
        assertEquals(OrderStatus.CANCELLED, repository.cancel(created.id, "user-1", "again", "user-1", "corr").status)
        assertEquals(404, assertFailsWith<ApiException> {
            repository.cancel("missing-order", "user-1", "missing", "user-1", "corr")
        }.statusCode)

        val paid = repository.create("user-1", request(initialStatus = OrderStatus.PAID), "paid", "actor", "corr")
        assertEquals(OrderStatus.CANCEL_REQUESTED, repository.cancel(paid.id, "user-1", "review", "user-1", "corr").status)
        val shipped = repository.create("user-1", request(initialStatus = OrderStatus.SHIPPED), "shipped", "actor", "corr")
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.cancel(shipped.id, "user-1", "too late", "user-1", "corr")
        }.errorCode)
        assertEquals(404, assertFailsWith<ApiException> {
            repository.transition("missing", null, OrderStatus.PAID, "actor", null, "corr")
        }.statusCode)
    }

    @Test
    fun `transition emits the event for every externally visible status`() {
        val transitions = listOf(
            OrderStatus.PAYMENT_PROCESSING to (OrderStatus.PAID to "OrderPaid"),
            OrderStatus.PAID to (OrderStatus.CONFIRMED to "OrderConfirmed"),
            OrderStatus.CONFIRMED to (OrderStatus.PROCESSING to "OrderProcessing"),
            OrderStatus.PROCESSING to (OrderStatus.SHIPPED to "OrderShipped"),
            OrderStatus.SHIPPED to (OrderStatus.OUT_FOR_DELIVERY to "OrderOutForDelivery"),
            OrderStatus.OUT_FOR_DELIVERY to (OrderStatus.DELIVERED to "OrderDelivered"),
            OrderStatus.CREATED to (OrderStatus.CANCELLED to "OrderCancelled"),
            OrderStatus.PAID to (OrderStatus.CANCEL_REQUESTED to "OrderCancellationRequested"),
            OrderStatus.SHIPPED to (OrderStatus.RETURN_REQUESTED to "OrderReturnRequested"),
            OrderStatus.REFUND_PENDING to (OrderStatus.REFUNDED to "OrderRefunded"),
        )

        transitions.forEachIndexed { index, (from, targetAndEvent) ->
            val (target, expectedEvent) = targetAndEvent
            val database = MemoryOrderDatabase()
            val repository = OrderRepository(database.dataSource())
            val order = repository.create("user-1", request(initialStatus = from), "event-$index", "actor", "corr")

            assertEquals(target, repository.transition(order.id, "user-1", target, "actor", null, "corr").status)
            assertEquals(expectedEvent, database.outbox.values.last().type)
        }
    }

    @Test
    fun `cancellation maps every cancellable order state to its implemented target`() {
        val cancellations = listOf(
            OrderStatus.VALIDATING to OrderStatus.CANCELLED,
            OrderStatus.INVENTORY_RESERVED to OrderStatus.CANCELLED,
            OrderStatus.PAYMENT_PENDING to OrderStatus.CANCELLED,
            OrderStatus.CONFIRMED to OrderStatus.CANCEL_REQUESTED,
            OrderStatus.PROCESSING to OrderStatus.CANCEL_REQUESTED,
        )

        cancellations.forEachIndexed { index, (initial, expected) ->
            val repository = OrderRepository(MemoryOrderDatabase().dataSource())
            val order = repository.create("user-1", request(initialStatus = initial), "cancel-$index", "actor", "corr")
            assertEquals(expected, repository.cancel(order.id, "user-1", "customer request", "user-1", "corr").status)
        }
    }

    @Test
    fun `delivered order return validates items persists return and is idempotent`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())
        val order = repository.create("user-1", request(initialStatus = OrderStatus.DELIVERED), "delivered", "actor", "corr")

        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.requestReturn(order.id, "user-1", ReturnRequest("", listOf(ReturnItemRequest("variant-1", 1))), "user-1", "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.requestReturn(order.id, "user-1", ReturnRequest("", emptyList()), "user-1", "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.requestReturn(order.id, "user-1", ReturnRequest("damaged", emptyList()), "user-1", "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.requestReturn(order.id, "user-1", ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 0))), "user-1", "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.requestReturn(order.id, "user-1", ReturnRequest("damaged", listOf(ReturnItemRequest("unknown", 1))), "user-1", "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.requestReturn(order.id, "user-1", ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 2))), "user-1", "corr")
        }.errorCode)

        val returned = repository.requestReturn(order.id, "user-1", ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 1))), "user-1", "corr")
        assertEquals("REQUESTED", returned.status)
        assertEquals(OrderStatus.RETURN_REQUESTED, repository.getOwned("user-1", order.id)!!.status)
        assertEquals("variant-1", database.returns.values.single().response.items.single().variantId)
        assertEquals(returned, repository.requestReturn(order.id, "user-1", ReturnRequest("new reason", listOf(ReturnItemRequest("variant-1", 1))), "user-1", "corr"))
        assertEquals(returned, repository.returnById("user-1", returned.id))
        assertNull(repository.returnById("user-2", returned.id))
    }

    @Test
    fun `pagination emits a cursor and rejects malformed cursors while outbox can be published`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())
        repository.create("user-1", request(), "page-1", "actor", "corr")
        repository.create("user-1", request(), "page-2", "actor", "corr")
        val firstPage = repository.listOwned("user-1", null, 1)
        assertEquals(1, firstPage.first.size)
        assertNotNull(firstPage.second)
        assertEquals(1, repository.listOwned("user-1", firstPage.second, 1).first.size)
        assertEquals(1, repository.listAll(firstPage.second, 1).first.size)
        assertFailsWith<ApiException> { repository.listAll("not-a-cursor", 1) }
        val all = repository.listAll(null, 100)
        assertTrue(all.first.size >= 2)
        assertNull(all.second)
        val outbox = repository.unpublished(100)
        repository.markPublished(outbox.map { it.id }, now)
        repository.markPublished(emptyList(), now)
        assertTrue(repository.unpublished(100).isEmpty())
    }

    @Test
    fun `create uses checkout fallback for a blank price version`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())

        repository.create("user-1", request(itemPriceVersion = ""), "blank-version", "actor", "corr")

        assertEquals("checkout-checkout-1", database.priceVersions.single())
    }

    @Test
    fun `create rolls back and preserves unexpected database failures`() {
        val database = MemoryOrderDatabase().also { it.orderInsertFailure = SQLException("database unavailable", "08001") }
        val repository = OrderRepository(database.dataSource())

        val failure = assertFailsWith<SQLException> {
            repository.create("user-1", request(), "database-failure", "actor", "corr")
        }

        assertEquals("08001", failure.sqlState)
        assertEquals(1, database.rollbacks)
        assertEquals(0, database.commits)
    }

    @Test
    fun `create maps a duplicate database constraint to a conflict`() {
        val database = MemoryOrderDatabase().also { it.orderInsertFailure = SQLException("duplicate", "23505") }
        val repository = OrderRepository(database.dataSource())

        val failure = assertFailsWith<ApiException> {
            repository.create("user-1", request(), "duplicate", "actor", "corr")
        }

        assertEquals(ErrorCode.CONFLICT, failure.errorCode)
        assertEquals(409, failure.statusCode)
        assertEquals(1, database.rollbacks)
    }

    @Test
    fun `return lookup handles multiple returned items and missing orders`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())
        val order = repository.create("user-1", request(initialStatus = OrderStatus.DELIVERED), "multi-return", "actor", "corr")
        val returnRequest = ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 1), ReturnItemRequest("variant-2", 1)))
        val twoItemOrder = order.copy(items = order.items + order.items.single().copy(variantId = "variant-2"))
        database.orders[order.id]!!.items += twoItemOrder.items.last()

        val returned = repository.requestReturn(order.id, "user-1", returnRequest, "user-1", "corr")

        assertEquals(listOf("variant-1", "variant-2"), repository.returnById("user-1", returned.id)!!.items.map { it.variantId })
        assertNull(repository.returnById("user-1", "missing-return"))
    }

    @Test
    fun `return request rejects missing and non-delivered orders`() {
        val repository = OrderRepository(MemoryOrderDatabase().dataSource())
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> {
            repository.requestReturn("missing-order", "user-1", ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 1))), "user-1", "corr")
        }.errorCode)

        val database = MemoryOrderDatabase()
        val created = OrderRepository(database.dataSource()).create("user-1", request(initialStatus = OrderStatus.CREATED), "not-delivered", "actor", "corr")
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            OrderRepository(database.dataSource()).requestReturn(created.id, "user-1", ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 1))), "user-1", "corr")
        }.errorCode)
    }

    @Test
    fun `a return can be recreated when a prior return exists but the order is delivered again`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())
        val order = repository.create("user-1", request(initialStatus = OrderStatus.DELIVERED), "return-recovery", "actor", "corr")
        val request = ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 1)))
        val first = repository.requestReturn(order.id, "user-1", request, "user-1", "corr")
        database.orders[order.id]!!.status = OrderStatus.DELIVERED

        val second = repository.requestReturn(order.id, "user-1", request, "user-1", "corr")

        assertTrue(second.id != first.id)
        assertEquals(2, database.returns.size)
    }

    @Test
    fun `return recovery tolerates a prior return row without return items`() {
        val database = MemoryOrderDatabase()
        val repository = OrderRepository(database.dataSource())
        val order = repository.create("user-1", request(initialStatus = OrderStatus.DELIVERED), "empty-return", "actor", "corr")
        database.returns["empty-return-row"] = MemoryOrderDatabase.ReturnRow(
            ReturnResponse("empty-return-row", order.id, "REQUESTED", "legacy", emptyList(), now.toString()),
            "user-1",
        )

        val recreated = repository.requestReturn(
            order.id,
            "user-1",
            ReturnRequest("damaged", listOf(ReturnItemRequest("variant-1", 1))),
            "user-1",
            "corr",
        )

        assertEquals("REQUESTED", recreated.status)
        assertEquals(2, database.returns.size)
    }

    @Test
    fun `pagination returns no cursor when stale page ids cannot be loaded`() {
        val database = MemoryOrderDatabase().also { it.pageIds = listOf("stale-1", "stale-2") }
        val repository = OrderRepository(database.dataSource())

        val page = repository.listAll(null, 1)

        assertTrue(page.first.isEmpty())
        assertNull(page.second)
    }

    private fun request(initialStatus: OrderStatus = OrderStatus.CREATED, promotion: PromotionSnapshot? = null, itemPriceVersion: String = "v1") = OrderCreateRequest(
        checkoutId = "checkout-1", reservationId = "reservation-1", items = listOf(OrderItemSnapshot("product-1", "variant-1", "Product", "SKU-1", "seller-1", 1, 1_000, 180, 0, 1_000, "INR", itemPriceVersion, mapOf("size" to "M"))),
        shippingAddress = address(), billingAddress = address(), subtotalMinor = 1_000, itemDiscountMinor = 0, promotionDiscountMinor = promotion?.discountMinor ?: 0, shippingMinor = 100, taxMinor = 180, totalMinor = 1_280 - (promotion?.discountMinor ?: 0), currency = "INR", promotion = promotion, initialStatus = initialStatus,
    )

    private fun address() = AddressSnapshot("address-1", "Customer", "+919999999999", "1 Main Street", city = "Pune", state = "MH", postalCode = "411001", country = "IN")
}

private class MemoryOrderDatabase {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }
    data class OrderRow(val id: String, val userId: String, val checkoutId: String, val reservationId: String?, var status: OrderStatus, val subtotal: Long, val itemDiscount: Long, val promotionDiscount: Long, val shipping: Long, val tax: Long, val total: Long, val currency: String, val key: String, val hash: String, val created: Timestamp, var updated: Timestamp, var version: Long = 1, val items: MutableList<OrderItemSnapshot> = mutableListOf(), val addresses: MutableMap<String, AddressSnapshot> = linkedMapOf(), var promotion: PromotionSnapshot? = null)
    data class ReturnRow(val response: ReturnResponse, val userId: String)
    data class OutboxRow(val id: String, val aggregateId: String, val type: String, val at: Timestamp, val correlation: String, val payload: String, var published: Boolean = false)

    val orders = linkedMapOf<String, OrderRow>()
    val returns = linkedMapOf<String, ReturnRow>()
    val outbox = linkedMapOf<String, OutboxRow>()
    val priceVersions = mutableListOf<String>()
    var pageIds: List<String>? = null
    var commits = 0
    var rollbacks = 0
    var orderInsertFailure: SQLException? = null

    fun dataSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ -> if (method.name == "getConnection") connection() else defaultValue(method.returnType) }) as DataSource

    private fun connection(): Connection {
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                "createArrayOf" -> sqlArray(args?.getOrNull(1))
                "setAutoCommit" -> null
                "commit" -> commits++
                "rollback" -> { rollbacks++; null }
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as Connection
    }

    private fun statement(raw: String): PreparedStatement {
        val sql = raw.replace(Regex("\\s+"), " ").trim().uppercase()
        val p = mutableMapOf<Int, Any?>()
        return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
            when {
                method.name.startsWith("set") && args?.firstOrNull() is Int -> { p[args[0] as Int] = args.getOrNull(1); null }
                method.name == "executeQuery" -> resultSet(query(sql, p))
                method.name == "executeUpdate" -> update(sql, p)
                method.name == "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as PreparedStatement
    }

    private fun query(sql: String, p: Map<Int, Any?>): List<Map<String, Any?>> = when {
        sql.startsWith("SELECT REQUEST_HASH,ID FROM ORDERS") -> orders.values.filter { it.userId == p[1] && it.key == p[2] }.map { mapOf("request_hash" to it.hash, "id" to it.id) }
        sql.startsWith("SELECT ID FROM ORDERS") -> (pageIds ?: orders.values.sortedByDescending { it.created }.map { it.id }).map { mapOf("id" to it) }
        sql.startsWith("SELECT * FROM ORDERS WHERE ID") -> orders[p[1]]?.takeIf { p[2] == null || it.userId == p[2] }?.let { listOf(orderMap(it)) } ?: emptyList()
        sql.startsWith("SELECT * FROM ORDER_ITEMS") -> orders[p[1]]?.items?.map { itemMap(it) } ?: emptyList()
        sql.startsWith("SELECT * FROM ORDER_ADDRESSES") -> orders[p[1]]?.addresses?.map { (type, address) -> addressMap(type, address) } ?: emptyList()
        sql.startsWith("SELECT * FROM ORDER_PROMOTION_SNAPSHOTS") -> orders[p[1]]?.promotion?.let { listOf(promotionMap(p[1].toString(), it)) } ?: emptyList()
        sql.startsWith("SELECT ID FROM ORDER_RETURNS") -> returns.values.filter { it.response.orderId == p[1] }.map { mapOf("id" to it.response.id) }
        sql.contains("FROM ORDER_RETURNS R") -> returns[p[1]]?.takeIf { p[2] == null || it.userId == p[2] }?.let { row -> row.response.items.map { item -> returnMap(row.response, row.userId, item) } } ?: emptyList()
        sql.startsWith("SELECT ID,AGGREGATE_ID,EVENT_TYPE") -> outbox.values.filterNot { it.published }.take((p[1] as? Int) ?: 100).map { mapOf("id" to it.id, "aggregate_id" to it.aggregateId, "event_type" to it.type, "schema_version" to 1, "occurred_at" to it.at, "correlation_id" to it.correlation, "payload_json" to it.payload) }
        else -> emptyList()
    }

    private fun update(sql: String, p: Map<Int, Any?>): Int = when {
        sql.startsWith("INSERT INTO ORDERS") -> { orderInsertFailure?.let { throw it }; val id = p[1].toString(); if (orders.values.any { it.userId == p[2] && it.key == p[13] }) throw SQLException("duplicate", "23505"); orders[id] = OrderRow(id, p[2].toString(), p[3].toString(), p[4] as? String, OrderStatus.valueOf(p[5].toString()), (p[6] as Number).toLong(), (p[7] as Number).toLong(), (p[8] as Number).toLong(), (p[9] as Number).toLong(), (p[10] as Number).toLong(), (p[11] as Number).toLong(), p[12].toString(), p[13].toString(), p[14].toString(), p[15] as Timestamp, p[15] as Timestamp); 1 }
        sql.startsWith("INSERT INTO ORDER_ITEMS") -> { orders[p[2]]?.items?.add(OrderItemSnapshot(p[3].toString(), p[4].toString(), p[5].toString(), p[6] as? String, p[7] as? String, (p[8] as Number).toInt(), (p[9] as Number).toLong(), (p[10] as Number).toLong(), (p[11] as Number).toLong(), (p[12] as Number).toLong(), p[13].toString(), p[14].toString(), json.decodeFromString(p[15].toString()))); 1 }
        sql.startsWith("INSERT INTO ORDER_ADDRESSES") -> { orders[p[1]]?.addresses?.put(p[2].toString(), AddressSnapshot(p[3].toString(), p[4].toString(), p[5].toString(), p[6].toString(), p[7] as? String, p[8].toString(), p[9].toString(), p[10].toString(), p[11].toString())); 1 }
        sql.startsWith("INSERT INTO ORDER_PROMOTION_SNAPSHOTS") -> { orders[p[1]]?.promotion = PromotionSnapshot(p[2] as? String, p[3] as? String, (p[4] as Number).toLong(), json.decodeFromString(p[5].toString())); 1 }
        sql.startsWith("UPDATE ORDERS SET STATUS='RETURN_REQUESTED'") -> orders[p[2]]?.let { it.status = OrderStatus.RETURN_REQUESTED; it.version++; 1 } ?: 0
        sql.startsWith("UPDATE ORDERS SET STATUS=") -> orders[p[3]]?.let { it.status = OrderStatus.valueOf(p[1].toString()); it.version++; it.updated = p[2] as Timestamp; 1 } ?: 0
        sql.startsWith("INSERT INTO ORDER_RETURNS") -> { val id = p[1].toString(); returns[id] = ReturnRow(ReturnResponse(id, p[2].toString(), p[3].toString(), p[4].toString(), emptyList(), (p[6] as Timestamp).toInstant().toString()), orders[p[2]]!!.userId); 1 }
        sql.startsWith("INSERT INTO ORDER_RETURN_ITEMS") -> { val key = p[1].toString(); val row = returns[key]!!; returns[key] = row.copy(response = row.response.copy(items = row.response.items + ReturnItemRequest(p[2].toString(), (p[3] as Number).toInt()))); 1 }
        sql.startsWith("INSERT INTO ORDER_STATUS_HISTORY") -> 1
        sql.startsWith("INSERT INTO ORDER_CANCELLATIONS") -> 1
        sql.startsWith("INSERT INTO ORDER_PRICE_SNAPSHOTS") -> { priceVersions += p[7].toString(); 1 }
        sql.startsWith("INSERT INTO ORDER_OUTBOX_EVENTS") -> { val id = p[1].toString(); outbox[id] = OutboxRow(id, p[2].toString(), p[3].toString(), p[4] as Timestamp, p[5].toString(), p[6].toString()); 1 }
        sql.startsWith("UPDATE ORDER_OUTBOX_EVENTS") -> { val ids = (p[2] as Array).getArray() as kotlin.Array<*>; ids.forEach { outbox[it.toString()]?.published = true }; 1 }
        else -> 1
    }

    private fun orderMap(row: OrderRow) = mapOf<String, Any?>("id" to row.id, "user_id" to row.userId, "checkout_id" to row.checkoutId, "reservation_id" to row.reservationId, "status" to row.status.name, "subtotal_minor" to row.subtotal, "item_discount_minor" to row.itemDiscount, "promotion_discount_minor" to row.promotionDiscount, "shipping_minor" to row.shipping, "tax_minor" to row.tax, "total_minor" to row.total, "currency" to row.currency, "version" to row.version, "created_at" to row.created, "updated_at" to row.updated)
    private fun itemMap(item: OrderItemSnapshot) = mapOf<String, Any?>("product_id" to item.productId, "variant_id" to item.variantId, "product_name" to item.productName, "sku" to item.sku, "seller_id" to item.sellerId, "quantity" to item.quantity, "unit_price_minor" to item.unitPriceMinor, "tax_minor" to item.taxMinor, "discount_minor" to item.discountMinor, "line_total_minor" to item.lineTotalMinor, "currency" to item.currency, "price_version" to item.priceVersion, "attributes_json" to json.encodeToString(item.attributes))
    private fun addressMap(type: String, address: AddressSnapshot) = mapOf<String, Any?>("address_type" to type, "address_id" to address.addressId, "recipient_name" to address.recipientName, "phone" to address.phone, "line1" to address.line1, "line2" to address.line2, "city" to address.city, "state" to address.state, "postal_code" to address.postalCode, "country" to address.country)
    private fun promotionMap(orderId: String, promotion: PromotionSnapshot) = mapOf<String, Any?>("order_id" to orderId, "promotion_id" to promotion.promotionId, "coupon_code" to promotion.couponCode, "discount_minor" to promotion.discountMinor, "allocation_json" to json.encodeToString(promotion.allocation))
    private fun returnMap(response: ReturnResponse, userId: String, item: ReturnItemRequest) = mapOf<String, Any?>("id" to response.id, "order_id" to response.orderId, "status" to response.status, "reason" to response.reason, "created_at" to Timestamp.from(Instant.parse(response.createdAt)), "user_id" to userId, "variant_id" to item.variantId, "quantity" to item.quantity)

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        var wasNull = false
        fun value(key: Any): Any? { val row = rows.getOrNull(index); val k = key.toString(); val actual = k.toIntOrNull()?.let { row?.keys?.elementAtOrNull(it - 1) } ?: k; val value = row?.get(actual); wasNull = value == null; return value }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args -> when (method.name) {
            "next" -> if (index + 1 < rows.size) { index++; true } else false
            "getString" -> value(args?.firstOrNull() ?: "")?.toString()
            "getInt" -> (value(args?.firstOrNull() ?: "") as? Number)?.toInt() ?: 0
            "getLong" -> (value(args?.firstOrNull() ?: "") as? Number)?.toLong() ?: 0L
            "getTimestamp" -> value(args?.firstOrNull() ?: "") as? Timestamp
            "wasNull" -> wasNull
            "close" -> null
            else -> defaultValue(method.returnType)
        } }) as ResultSet
    }

    private fun sqlArray(values: Any?): Array = Proxy.newProxyInstance(Array::class.java.classLoader, arrayOf(Array::class.java), InvocationHandler { _, method, _ -> if (method.name == "getArray") values else defaultValue(method.returnType) }) as Array
    private fun defaultValue(type: Class<*>): Any? = when (type) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
}
