package com.ecommerce.seller

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.kafka.EventEnvelope
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
import kotlinx.serialization.json.Json

class SellerRepositoryPersistenceTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    @Test
    fun `onboarding profile update and lifecycle enforce ownership and optimistic version`() {
        val db = MemorySellerDatabase()
        val repository = SellerRepository(db.dataSource(), json)
        val created = repository.create("owner-1", SellerApplication("  Shop  ", "  Shop Legal  ", " OWNER@EXAMPLE.COM ", "+911234"), "owner-1", "corr")
        assertEquals("Shop", created.displayName)
        assertEquals("shop legal", created.legalName.lowercase())
        assertEquals("owner@example.com", created.email)
        assertEquals(SellerStatus.PENDING, created.status)
        assertEquals(created, repository.byUser("owner-1"))
        assertNull(repository.byUser("other"))
        assertEquals(created, repository.get(created.id))
        assertNull(repository.get("missing-seller"))

        val updated = repository.update("owner-1", SellerProfileUpdate("New Shop", "New Legal", "+915555", created.version))
        assertEquals("New Shop", updated.displayName)
        assertEquals(2L, updated.version)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.update("owner-1", SellerProfileUpdate("Stale", "Legal", null, created.version))
        }.errorCode)
        assertEquals(404, assertFailsWith<ApiException> {
            repository.update("missing", SellerProfileUpdate("x", "y", null, 1))
        }.statusCode)

        assertEquals(SellerStatus.UNDER_REVIEW, repository.transition(created.id, SellerStatus.UNDER_REVIEW, "reviewer", null, "corr").status)
        assertEquals(SellerStatus.VERIFIED, repository.transition(created.id, SellerStatus.VERIFIED, "reviewer", "approved", "corr").status)
        assertEquals(SellerStatus.ACTIVE, repository.transition(created.id, SellerStatus.ACTIVE, "admin", null, "corr").status)
        assertEquals(SellerStatus.ACTIVE, repository.transition(created.id, SellerStatus.ACTIVE, "admin", null, "corr").status)
        assertEquals(SellerStatus.SUSPENDED, repository.transition(created.id, SellerStatus.SUSPENDED, "admin", "compliance hold", "corr").status)
        assertEquals(SellerStatus.ACTIVE, repository.transition(created.id, SellerStatus.ACTIVE, "admin", "restored", "corr").status)
        assertEquals(SellerStatus.CLOSED, repository.transition(created.id, SellerStatus.CLOSED, "admin", "closed", "corr").status)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.transition(created.id, SellerStatus.REJECTED, "admin", "invalid", "corr")
        }.errorCode)
        assertEquals(404, assertFailsWith<ApiException> {
            repository.transition("missing", SellerStatus.ACTIVE, "admin", null, "corr")
        }.statusCode)
        val pending = repository.unpublished(100)
        assertTrue(pending.isNotEmpty())
        repository.markPublished(pending.map { it.id }, Instant.parse("2026-08-20T00:00:00Z"))
        assertTrue(repository.unpublished(100).isEmpty())
    }

    @Test
    fun `ledger validates entries and exposes exact financial transactions`() {
        val db = MemorySellerDatabase()
        val repository = SellerRepository(db.dataSource(), json)
        val seller = repository.create("owner-1", SellerApplication("Shop", "Legal", "shop@example.com"), "owner", "corr")
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.addLedger(seller.id, LedgerEntryRequest(LedgerEntryType.SALE, "", 100), "actor")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.addLedger(seller.id, LedgerEntryRequest(LedgerEntryType.REFUND, "ref-1", 100, "IN"), "actor")
        }.errorCode)
        val entry = repository.addLedger(seller.id, LedgerEntryRequest(LedgerEntryType.SALE, "order-1", 12_500, "inr", "sale description"), "actor")
        assertEquals(LedgerEntryType.SALE, entry.entryType)
        assertEquals(12_500L, entry.amountMinor)
        assertEquals("INR", entry.currency)
        assertEquals(entry, repository.ledger(seller.id).single())
        assertTrue(repository.ledger("other").isEmpty())

        val otherTypes = listOf(
            LedgerEntryType.COMMISSION to "commission-1",
            LedgerEntryType.REFUND to "refund-1",
            LedgerEntryType.ADJUSTMENT to "adjustment-1",
            LedgerEntryType.PAYOUT to "payout-1",
        ).mapIndexed { index, (type, reference) ->
            repository.addLedger(seller.id, LedgerEntryRequest(type, reference, index.toLong() + 1, "INR"), "actor")
        }
        assertEquals(
            listOf(LedgerEntryType.COMMISSION, LedgerEntryType.REFUND, LedgerEntryType.ADJUSTMENT, LedgerEntryType.PAYOUT),
            otherTypes.map { it.entryType },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), otherTypes.map { it.amountMinor })
    }

    @Test
    fun `order events are deduplicated and preserve seller isolation`() {
        val db = MemorySellerDatabase()
        val repository = SellerRepository(db.dataSource(), json)
        val event = event("event-1", "OrderCreated", """
            {"id":"order-1","status":"PAID","items":[
              {"sellerId":"seller-a","productId":"product-a","variantId":"variant-a","quantity":2,"lineTotalMinor":2000,"currency":"INR"},
              {"sellerId":"seller-b","productId":"product-b","variantId":"variant-b","quantity":1,"lineTotalMinor":900,"currency":"INR"},
              {"productId":"missing-seller","variantId":"variant-x"}
            ]}
        """.trimIndent())
        repository.apply(event)
        repository.apply(event)
        assertEquals(2, db.sellerItems.size)
        assertEquals(1, repository.orders("seller-a", 100).size)
        assertEquals("seller-a", db.sellerItems.single { it.seller == "seller-a" }.seller)
        repository.apply(event("bad", "OrderCreated", "not-json"))
        repository.apply(event("array", "OrderCreated", "[]"))
        repository.apply(event("empty", "OrderCreated", "{" + "\"items\":[]}"))
        assertEquals(2, db.sellerItems.size)
        assertEquals(900, repository.orders("seller-b", 100).single().lineTotalMinor)
    }

    @Test
    fun `order event ingestion supports id fallbacks and safely skips incomplete items`() {
        val db = MemorySellerDatabase()
        val repository = SellerRepository(db.dataSource(), json)
        repository.apply(event("order-id", "OrderCreated", """{"orderId":"order-2","items":[{"sellerId":"seller-a","productId":"product-a","variantId":"variant-a"}]}"""))
        repository.apply(event("aggregate-id", "OrderCreated", """{"status":"SHIPPED","items":[{"sellerId":"seller-a","productId":"product-a","variantId":"variant-b","quantity":2}]}"""))
        val beforeIncomplete = db.sellerItems.size
        repository.apply(event("missing-product", "OrderCreated", """{"items":[{"sellerId":"seller-a","variantId":"variant-x"}]}"""))
        repository.apply(event("missing-variant", "OrderCreated", """{"items":[{"sellerId":"seller-a","productId":"product-a"}]}"""))
        repository.apply(event("missing-items", "OrderCreated", "{}"))
        repository.apply(event("null-fields", "OrderCreated", """{"id":null,"orderId":null,"items":[{"sellerId":null,"productId":null,"variantId":null,"quantity":null,"lineTotalMinor":null,"currency":null}]}"""))
        repository.apply(event("scalar-item", "OrderCreated", """{"items":[1,{"sellerId":"seller-a","productId":"product-a","variantId":"variant-a"}]}"""))

        assertEquals(beforeIncomplete + 1, db.sellerItems.size)
        assertTrue(db.sellerItems.any { it.eventId == "scalar-item" && it.product == "product-a" })
        assertEquals("order-2", db.sellerItems.first { it.eventId == "order-id" }.orderId)
        assertEquals("order-1", db.sellerItems.first { it.eventId == "aggregate-id" }.orderId)
        assertEquals(0, db.sellerItems.first { it.eventId == "order-id" }.quantity)
        assertEquals("INR", db.sellerItems.first { it.eventId == "order-id" }.currency)
    }

    @Test
    fun `dead letters normalize valid payload and preserve malformed payload safely`() {
        val db = MemorySellerDatabase()
        val repository = SellerRepository(db.dataSource(), json)
        repository.deadLetter(event("dead-1", "OrderCreated", "{\"secret\":true}"), IllegalStateException("provider failed"))
        repository.deadLetter(null, RuntimeException("unknown"))
        repository.deadLetter(event("dead-2", "OrderCreated", "{"), object : Throwable() {})
        repository.deadLetter(null, object : Throwable() {})
        assertEquals(4, db.dlq.size)
        assertEquals("provider failed", db.dlq.first().error)
        assertEquals("{\"secret\":true}", db.dlq.first().payload)
        assertNull(db.dlq.last().payload)
        assertTrue(db.dlq.last().error.isNotBlank())
        val outbox = repository.unpublished(100)
        assertTrue(outbox.isEmpty())
        repository.markPublished(emptyList(), Instant.now())
    }

    private fun event(id: String, type: String, payload: String) = EventEnvelope(id, type, 1, "2026-08-20T10:15:00Z", "orders", "tenant-1", "Order", "order-1", "corr", payloadJson = payload)
}

private class MemorySellerDatabase {
    data class SellerRow(val id: String, val owner: String, var display: String, var legal: String, val email: String, var phone: String?, var status: SellerStatus, var version: Long = 1, val created: Timestamp = Timestamp.from(Instant.now()), var updated: Timestamp = Timestamp.from(Instant.now()))
    data class Item(val eventId: String, val orderId: String, val seller: String, val product: String, val variant: String, val quantity: Int, val amount: Long, val currency: String, val status: String?, val occurred: Timestamp)
    data class Ledger(val id: String, val seller: String, val type: LedgerEntryType, val reference: String, val amount: Long, val currency: String, val description: String?, val created: Timestamp = Timestamp.from(Instant.now()))
    data class Dlq(val eventId: String?, val type: String?, val aggregate: String?, val payload: String?, val error: String)
    data class Outbox(val id: String, val aggregate: String, val type: String, val occurred: Timestamp, val correlation: String, val payload: String, var published: Boolean = false)

    val sellers = linkedMapOf<String, SellerRow>()
    val users = linkedMapOf<String, String>()
    val sellerItems = mutableListOf<Item>()
    val ledger = mutableListOf<Ledger>()
    val inbox = mutableSetOf<String>()
    val dlq = mutableListOf<Dlq>()
    val outbox = mutableListOf<Outbox>()

    fun dataSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ -> if (method.name == "getConnection") connection() else defaultValue(method.returnType) }) as DataSource

    private fun connection(): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args -> when (method.name) {
        "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
        "createArrayOf" -> sqlArray(args?.getOrNull(1))
        "setAutoCommit", "commit", "rollback", "close" -> null
        else -> defaultValue(method.returnType)
    } }) as Connection

    private fun statement(raw: String): PreparedStatement {
        val sql = raw.replace(Regex("\\s+"), " ").trim().uppercase()
        val p = mutableMapOf<Int, Any?>()
        return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args -> when {
            method.name.startsWith("set") && args?.firstOrNull() is Int -> { p[args[0] as Int] = args.getOrNull(1); null }
            method.name == "executeQuery" -> resultSet(query(sql, p))
            method.name == "executeUpdate" -> update(sql, p)
            method.name == "close" -> null
            else -> defaultValue(method.returnType)
        } }) as PreparedStatement
    }

    private fun query(sql: String, p: Map<Int, Any?>): List<Map<String, Any?>> = when {
        sql.startsWith("SELECT S.* FROM SELLERS S JOIN SELLER_USERS") -> users.entries.firstOrNull { it.key == p[1] }?.value?.let { sellers[it] }?.let { listOf(sellerMap(it)) } ?: emptyList()
        sql.startsWith("SELECT * FROM SELLERS WHERE ID") -> sellers[p[1]]?.let { listOf(sellerMap(it)) } ?: emptyList()
        sql.startsWith("SELECT ORDER_ID,PRODUCT_ID,VARIANT_ID") -> sellerItems.filter { it.seller == p[1] }.take((p[2] as? Int) ?: 100).map { mapOf("order_id" to it.orderId, "product_id" to it.product, "variant_id" to it.variant, "quantity" to it.quantity, "line_total_minor" to it.amount, "currency" to it.currency, "order_status" to it.status, "occurred_at" to it.occurred) }
        sql.startsWith("SELECT ID,ENTRY_TYPE,REFERENCE_ID") && sql.contains("AND ENTRY_TYPE") -> ledger.firstOrNull { it.seller == p[1] && it.type.name == p[2] && it.reference == p[3] }?.let { listOf(mapOf("id" to it.id, "entry_type" to it.type.name, "reference_id" to it.reference, "amount_minor" to it.amount, "currency" to it.currency, "description" to it.description, "created_at" to it.created)) } ?: emptyList()
        sql.startsWith("SELECT ID,ENTRY_TYPE,REFERENCE_ID") -> ledger.filter { it.seller == p[1] }.map { mapOf("id" to it.id, "entry_type" to it.type.name, "reference_id" to it.reference, "amount_minor" to it.amount, "currency" to it.currency, "description" to it.description, "created_at" to it.created) }
        sql.startsWith("SELECT ID,AGGREGATE_TYPE,AGGREGATE_ID") -> outbox.filterNot { it.published }.take((p[1] as? Int) ?: 100).map { mapOf("id" to it.id, "aggregate_type" to "Seller", "aggregate_id" to it.aggregate, "event_type" to it.type, "schema_version" to 1, "occurred_at" to it.occurred, "correlation_id" to it.correlation, "payload_json" to it.payload) }
        else -> emptyList()
    }

    private fun update(sql: String, p: Map<Int, Any?>): Int = when {
        sql.startsWith("INSERT INTO SELLER_INBOX_EVENTS") -> if (inbox.add(p[1].toString())) 1 else 0
        sql.startsWith("INSERT INTO SELLER_ORDER_ITEMS") -> { sellerItems += Item(p[1].toString(), p[2].toString(), p[3].toString(), p[4].toString(), p[5].toString(), (p[6] as Number).toInt(), (p[7] as Number).toLong(), p[8].toString(), p[9] as? String, p[10] as Timestamp); 1 }
        sql.startsWith("INSERT INTO SELLERS") -> { val row = SellerRow(p[1].toString(), p[2].toString(), p[3].toString(), p[4].toString(), p[5].toString(), p[6] as? String, SellerStatus.valueOf(p[7].toString())); sellers[row.id] = row; 1 }
        sql.startsWith("INSERT INTO SELLER_USERS") -> { users[p[2].toString()] = p[1].toString(); 1 }
        sql.startsWith("INSERT INTO SELLER_VERIFICATION") -> 1
        sql.startsWith("UPDATE SELLERS SET DISPLAY_NAME") -> sellers[p[4]]?.takeIf { it.version == (p[5] as Number).toLong() }?.let { it.display = p[1].toString(); it.legal = p[2].toString(); it.phone = p[3] as? String; it.version++; 1 } ?: 0
        sql.startsWith("UPDATE SELLERS SET STATUS") -> sellers[p[2]]?.let { it.status = SellerStatus.valueOf(p[1].toString()); it.version++; 1 } ?: 0
        sql.startsWith("UPDATE SELLER_VERIFICATION") -> 1
        sql.startsWith("INSERT INTO SELLER_LEDGER") -> { val row = Ledger(p[1].toString(), p[2].toString(), LedgerEntryType.valueOf(p[3].toString()), p[4].toString(), (p[5] as Number).toLong(), p[6].toString(), p[7] as? String); if (ledger.none { it.seller == row.seller && it.type == row.type && it.reference == row.reference }) ledger += row; 1 }
        sql.startsWith("INSERT INTO SELLER_DLQ") -> { dlq += Dlq(p[1] as? String, p[2] as? String, p[3] as? String, p[4] as? String, p[5].toString()); 1 }
        sql.startsWith("INSERT INTO SELLER_OUTBOX_EVENTS") -> { outbox += Outbox(p[1].toString(), p[3].toString(), p[4].toString(), p[5] as Timestamp, p[6].toString(), p[7].toString()); 1 }
        sql.startsWith("UPDATE SELLER_OUTBOX_EVENTS") -> { val ids = (p[2] as Array).getArray() as kotlin.Array<*>; outbox.filter { ids.any { id -> id.toString() == it.id } }.forEach { it.published = true }; 1 }
        else -> 1
    }

    private fun sellerMap(row: SellerRow) = mapOf<String, Any?>("id" to row.id, "owner_user_id" to row.owner, "display_name" to row.display, "legal_name" to row.legal, "email" to row.email, "phone" to row.phone, "status" to row.status.name, "version" to row.version, "created_at" to row.created, "updated_at" to row.updated)

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
