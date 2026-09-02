package com.ecommerce.review

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.kafka.EventEnvelope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ReviewRepositoryBehaviorTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `order ingestion creates verified purchase facts once and ignores malformed events`() {
        val database = MemoryReviewDatabase()
        val repository = ReviewRepository(database.dataSource(), json, autoPublish = true)
        val event = event("order-evt", """
            {"userId":"user-1","id":"order-1","items":[
              {"productId":"product-1","variantId":"variant-1","sellerId":"seller-1"},
              {"productId":"product-2","sellerId":"seller-2"},
              {"variantId":"missing-product"}
            ]}
        """.trimIndent())
        repository.accept(event)
        repository.accept(event)
        assertEquals(2, database.purchases.size)
        repository.accept(event("missing-user", "{\"items\":[]}"))
        repository.accept(event("malformed", "not-json"))
        assertEquals(2, database.purchases.size)
    }

    @Test
    fun `order ingestion accepts alternate identifiers and ignores incomplete item payloads`() {
        val database = MemoryReviewDatabase()
        val repository = ReviewRepository(database.dataSource(), json, autoPublish = true)

        repository.accept(event("snake-case", """
            {"user_id":"user-2","orderId":"order-2","items":[
              {"productId":"product-2"},
              {"variantId":"without-product"}
            ]}
        """.trimIndent()))
        repository.accept(event("aggregate-fallback", """
            {"userId":"user-3","items":[{"productId":"product-3","sellerId":"seller-3","variantId":"variant-3"}]}
        """.trimIndent()))
        repository.accept(event("missing-items", """{"userId":"user-4"}"""))
        repository.accept(event("null-items", """{"userId":"user-null-items","items":null}"""))
        repository.accept(event("object-items", """{"userId":"user-5","items":{}}"""))
        repository.accept(event("scalar-items", """{"userId":"user-7","items":"not-an-array"}"""))
        repository.accept(event("null-identifiers", """
            {"userId":null,"user_id":"user-6","id":null,"orderId":"order-6","items":[]}
        """.trimIndent()))

        assertEquals(
            listOf("product-2", "product-3"),
            database.purchases.map { it.product },
        )
        assertEquals("order-2", database.purchases.first().order)
        assertEquals("order-1", database.purchases.last().order)
    }

    @Test
    fun `order ingestion skips scalar items without dropping valid purchase facts`() {
        val database = MemoryReviewDatabase()
        val repository = ReviewRepository(database.dataSource(), json, autoPublish = true)

        repository.accept(event("scalar-item", """
            {"userId":"user-8","orderId":"order-8","items":[1,{"productId":"product-8"}]}
        """.trimIndent()))

        assertEquals(listOf("product-8"), database.purchases.map { it.product })
        assertEquals("order-8", database.purchases.single().order)
    }

    @Test
    fun `order ingestion ignores explicitly null optional item identifiers`() {
        val database = MemoryReviewDatabase()
        val repository = ReviewRepository(database.dataSource(), json, autoPublish = true)

        repository.accept(event("null-item-fields", """
            {"userId":"user-9","orderId":"order-9","items":[
              {"productId":"product-9","sellerId":null,"variantId":null},
              {"productId":null,"sellerId":"seller-9"}
            ]}
        """.trimIndent()))

        assertEquals(listOf("product-9"), database.purchases.map { it.product })
        assertNull(database.purchases.single().seller)
    }

    @Test
    fun `create requires verified purchase validates ratings and enforces rate limit`() {
        val database = MemoryReviewDatabase()
        database.purchases += Purchase("user-1", "order-1", "product-1", "seller-1")
        val repository = ReviewRepository(database.dataSource(), json, autoPublish = false)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.create("user-1", ReviewRequest(ReviewTarget.PRODUCT, "", "order-1", 5, body = "body"))
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.create("user-1", ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 6, body = "body"))
        }.errorCode)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.create("other", ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 5, body = "body"))
        }.errorCode)
        database.recentCount = 5
        assertEquals(429, assertFailsWith<ApiException> {
            repository.create("user-1", ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 5, body = "body"))
        }.statusCode)
    }

    @Test
    fun `published and submitted reviews support votes reports moderation and exact aggregation`() {
        val database = MemoryReviewDatabase()
        database.purchases += Purchase("user-1", "order-1", "product-1", "seller-1")
        database.purchases += Purchase("user-2", "order-2", "product-1", "seller-1")
        val publishedRepository = ReviewRepository(database.dataSource(), json, autoPublish = true)
        val first = publishedRepository.create("user-1", ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 5, "Great", "Excellent product"))
        assertEquals(ReviewStatus.PUBLISHED, first.status)
        publishedRepository.vote("user-2", first.id)
        publishedRepository.vote("user-2", first.id)
        assertEquals(1, database.votes.size)
        publishedRepository.report("user-2", first.id, ReportRequest("unsafe listing"))
        publishedRepository.report("user-2", first.id, ReportRequest("duplicate report"))
        assertEquals(1, database.reports.size)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            publishedRepository.report("user-2", first.id, ReportRequest(" "))
        }.errorCode)

        assertEquals(RatingAggregate(ReviewTarget.PRODUCT, "product-1", 1, 5.0), publishedRepository.aggregate(ReviewTarget.PRODUCT, "product-1"))
        val page = publishedRepository.list(ReviewTarget.PRODUCT, "product-1", 0, 10)
        assertEquals(first.id, page.items.single().id)
        val hidden = publishedRepository.moderate(first.id, ModerationRequest(ReviewStatus.HIDDEN, "policy"), "moderator")
        assertEquals(ReviewStatus.HIDDEN, hidden.status)
        assertEquals(0L, publishedRepository.aggregate(ReviewTarget.PRODUCT, "product-1").count)
        assertEquals(ReviewStatus.HIDDEN, publishedRepository.moderate(first.id, ModerationRequest(ReviewStatus.HIDDEN), "moderator").status)
        assertEquals(ReviewStatus.PUBLISHED, publishedRepository.moderate(first.id, ModerationRequest(ReviewStatus.PUBLISHED), "moderator").status)

        val submitted = ReviewRepository(database.dataSource(), json, autoPublish = false).create(
            "user-2", ReviewRequest(ReviewTarget.SELLER, "seller-1", "order-2", 3, body = "Seller feedback"),
        )
        assertEquals(ReviewStatus.SUBMITTED, submitted.status)
        assertEquals(404, assertFailsWith<ApiException> {
            publishedRepository.moderate("missing", ModerationRequest(ReviewStatus.HIDDEN), "moderator")
        }.statusCode)

        val submittedModerated = publishedRepository.moderate(submitted.id, ModerationRequest(ReviewStatus.HIDDEN), "moderator")
        assertEquals(ReviewStatus.HIDDEN, submittedModerated.status)
        val submittedPublished = publishedRepository.moderate(submitted.id, ModerationRequest(ReviewStatus.PUBLISHED), "moderator")
        assertEquals(ReviewStatus.PUBLISHED, submittedPublished.status)
    }

    @Test
    fun `review listing clamps pagination and reports a next cursor when there are more rows`() {
        val database = MemoryReviewDatabase()
        database.purchases += Purchase("user-1", "order-1", "product-1", "seller-1")
        database.purchases += Purchase("user-2", "order-2", "product-1", "seller-1")
        val repository = ReviewRepository(database.dataSource(), json, autoPublish = true)
        repository.create("user-1", ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-1", 5, body = "first"))
        repository.create("user-2", ReviewRequest(ReviewTarget.PRODUCT, "product-1", "order-2", 4, body = "second"))

        val page = repository.list(ReviewTarget.PRODUCT, "product-1", cursor = -4, limit = 0)
        assertEquals(1, page.items.size)
        assertEquals("-3", page.nextCursor)
        assertEquals(2, repository.list(ReviewTarget.PRODUCT, "product-1", cursor = 0, limit = 100).items.size)
    }

    @Test
    fun `review DLQ records provider failures without requiring the event payload`() {
        val database = MemoryReviewDatabase()
        val repository = ReviewRepository(database.dataSource(), json, autoPublish = false)
        repository.dlq(event("dead", "{}"), IllegalStateException("consumer failed"))
        repository.dlq(null, RuntimeException("unknown"))
        repository.dlq(event("dead-no-message", "{}"), RuntimeException())
        repository.dlq(event("dead-long", "{}"), RuntimeException("x".repeat(2_001)))
        assertEquals(listOf("consumer failed", "unknown", "consumer failure", "x".repeat(2_000)), database.dlq)
        assertNull(repository.aggregate(ReviewTarget.SELLER, "missing").takeIf { it.count > 0 })
        assertTrue(database.commits == 0)
    }

    private fun event(id: String, payload: String) = EventEnvelope(id, "OrderCreated", 1, "2026-08-20T10:15:00Z", "order-service", "tenant-1", "Order", "order-1", "corr", payloadJson = payload)
}

private data class Purchase(val user: String, val order: String, val product: String, val seller: String?)

private class MemoryReviewDatabase {
    val purchases = mutableListOf<Purchase>()
    val reviews = linkedMapOf<String, ReviewResponse>()
    val votes = mutableSetOf<Pair<String, String>>()
    val reports = mutableSetOf<Pair<String, String>>()
    val inbox = mutableSetOf<String>()
    val dlq = mutableListOf<String>()
    var recentCount = 0L
    var commits = 0
    private val createdAt = Timestamp.from(Instant.parse("2026-08-20T10:00:00Z"))

    fun dataSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ -> if (method.name == "getConnection") connection() else defaultValue(method.returnType) }) as DataSource

    private fun connection(): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args -> when (method.name) {
        "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
        "setAutoCommit" -> null
        "commit" -> commits++
        "rollback", "close" -> null
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
        sql.startsWith("SELECT 1 FROM REVIEW_PURCHASE_FACTS") -> if (purchases.any { it.user == p[1] && it.order == p[2] && (if (sql.contains("PRODUCT_ID")) it.product == p[3] else it.seller == p[3]) }) listOf(mapOf("one" to 1)) else emptyList()
        sql.startsWith("SELECT COUNT(*) FROM REVIEWS WHERE USER_ID") -> listOf(mapOf("count" to recentCount))
        sql.startsWith("SELECT ID FROM REVIEWS") -> reviews.values.filter { it.targetType.name == p[1] && it.targetId == p[2] && it.status == ReviewStatus.PUBLISHED }.map { mapOf("id" to it.id) }
        sql.startsWith("SELECT REVIEW_COUNT,AVERAGE") -> reviews.values.filter { it.targetType.name == p[1] && it.targetId == p[2] && it.status == ReviewStatus.PUBLISHED }.let { rows -> if (rows.isEmpty()) emptyList() else listOf(mapOf("review_count" to rows.size.toLong(), "average" to rows.map { it.rating }.average())) }
        sql.startsWith("SELECT R.*") -> reviews[p[1]]?.let { listOf(reviewMap(it)) } ?: emptyList()
        else -> emptyList()
    }

    private fun update(sql: String, p: Map<Int, Any?>): Int = when {
        sql.startsWith("INSERT INTO REVIEW_INBOX_EVENTS") -> if (inbox.add(p[1].toString())) 1 else 0
        sql.startsWith("INSERT INTO REVIEW_PURCHASE_FACTS") -> { purchases += Purchase(p[1].toString(), p[2].toString(), p[3].toString(), p[5] as? String); 1 }
        sql.startsWith("INSERT INTO REVIEWS") -> { val id = p[1].toString(); reviews[id] = ReviewResponse(id, p[2].toString(), ReviewTarget.valueOf(p[3].toString()), p[4].toString(), p[5].toString(), (p[6] as Number).toInt(), p[7] as? String, p[8].toString(), true, ReviewStatus.valueOf(p[10].toString()), 0, createdAt.toInstant().toString()); 1 }
        sql.startsWith("INSERT INTO REVIEW_HELPFUL_VOTES") -> { votes.add(p[2].toString() to p[1].toString()); 1 }
        sql.startsWith("INSERT INTO REVIEW_REPORTS") -> { reports.add(p[2].toString() to p[1].toString()); 1 }
        sql.startsWith("UPDATE REVIEWS SET STATUS") -> reviews[p[2]]?.let { reviews[p[2].toString()] = it.copy(status = ReviewStatus.valueOf(p[1].toString())); 1 } ?: 0
        sql.startsWith("DELETE FROM REVIEW_RATING_AGGREGATES") -> 1
        sql.startsWith("INSERT INTO REVIEW_RATING_AGGREGATES") -> 1
        sql.startsWith("INSERT INTO REVIEW_MODERATION_AUDIT") -> 1
        sql.startsWith("INSERT INTO REVIEW_DLQ") -> { dlq += (p[2] as? String) ?: "consumer failure"; 1 }
        else -> 1
    }

    private fun reviewMap(review: ReviewResponse) = mapOf<String, Any?>(
        "id" to review.id, "user_id" to review.userId, "target_type" to review.targetType.name, "target_id" to review.targetId, "order_id" to review.orderId,
        "rating" to review.rating, "title" to review.title, "body" to review.body, "verified_purchase" to review.verifiedPurchase, "status" to review.status.name,
        "helpful_count" to votes.count { it.second == review.id }, "created_at" to Timestamp.from(Instant.parse(review.createdAt)),
    )

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        var wasNull = false
        fun value(key: Any): Any? { val row = rows.getOrNull(index); val k = key.toString(); val actual = k.toIntOrNull()?.let { row?.keys?.elementAtOrNull(it - 1) } ?: k; val value = row?.get(actual); wasNull = value == null; return value }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args -> when (method.name) {
            "next" -> if (index + 1 < rows.size) { index++; true } else false
            "getString" -> value(args?.firstOrNull() ?: "")?.toString()
            "getInt" -> (value(args?.firstOrNull() ?: "") as? Number)?.toInt() ?: 0
            "getLong" -> (value(args?.firstOrNull() ?: "") as? Number)?.toLong() ?: 0L
            "getDouble" -> (value(args?.firstOrNull() ?: "") as? Number)?.toDouble() ?: 0.0
            "getBoolean" -> value(args?.firstOrNull() ?: "") as? Boolean ?: false
            "getTimestamp" -> value(args?.firstOrNull() ?: "") as? Timestamp
            "wasNull" -> wasNull
            "close" -> null
            else -> defaultValue(method.returnType)
        } }) as ResultSet
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; Double::class.javaPrimitiveType -> 0.0; else -> null }
}
