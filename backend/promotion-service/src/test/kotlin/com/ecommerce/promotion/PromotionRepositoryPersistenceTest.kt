package com.ecommerce.promotion

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class PromotionRepositoryPersistenceTest {
    private val now = Instant.parse("2020-01-01T00:00:00Z")

    @Test
    fun `create update archive list and outbox publication persist the complete lifecycle`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())

        val created = repository.create(
            PromotionRequest(
                name = "  Seller sale  ", type = PromotionType.PERCENTAGE, status = PromotionStatus.ACTIVE,
                startAt = now.toString(), percentageBps = 1_000, sellerIds = listOf("seller-a"),
            ), "admin-1", "corr-create",
        )
        assertEquals("Seller sale", created.name)
        assertEquals(PromotionStatus.ACTIVE, created.status)
        assertEquals(1L, created.version)
        assertEquals(listOf("seller-a"), database.promotions[created.id]!!.response.sellerIds)
        assertEquals(listOf(created), repository.listForSeller("seller-a", 500))
        database.orphanSellerRows = true
        assertTrue(repository.listForSeller("orphan", 1).isEmpty())

        val updated = repository.update(
            created.id,
            PromotionRequest(
                name = "Updated sale", type = PromotionType.FIXED_AMOUNT, status = PromotionStatus.PAUSED,
                startAt = now.toString(), fixedAmountMinor = 250, endAt = null,
            ), "admin-2", "corr-update",
        )
        assertEquals("Updated sale", updated.name)
        assertEquals(PromotionType.FIXED_AMOUNT, updated.type)
        assertEquals(PromotionStatus.PAUSED, updated.status)
        assertEquals(2L, updated.version)
        assertEquals(emptyList(), repository.listForSeller("seller-a", 1))

        val archived = repository.archive(created.id, "admin-3", "corr-archive")
        assertEquals(PromotionStatus.ARCHIVED, archived.status)
        assertEquals(3L, archived.version)

        val unpublished = repository.unpublished(100)
        assertEquals(3, unpublished.size)
        assertEquals(listOf("PromotionCreated", "PromotionUpdated", "PromotionArchived"), unpublished.map { it.eventType })
        repository.markPublished(unpublished.map { it.id }, now)
        repository.markPublished(emptyList(), now)
        assertTrue(repository.unpublished(100).isEmpty())
        assertEquals(4, database.commits)
    }

    @Test
    fun `coupon lifecycle normalizes code maps conflicts and handles missing rows`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val promotion = createActive(repository)

        val coupon = repository.createCoupon(CouponRequest(promotion.id, "  save-10  ", usageLimit = 2, perUserLimit = 1), "admin", "corr")
        assertEquals("save-10", coupon.code)
        assertEquals(2L, coupon.usageLimit)
        assertEquals(0L, coupon.usageCount)
        val unlimited = repository.createCoupon(CouponRequest(promotion.id, "unlimited"), "admin", "corr")
        assertEquals(null, unlimited.usageLimit)
        assertEquals(null, unlimited.perUserLimit)

        val updated = repository.updateCoupon(coupon.id, CouponUpdateRequest("DISABLED", usageLimit = 4, perUserLimit = 3), "admin", "corr")
        assertEquals("DISABLED", updated.status)
        assertEquals(4L, updated.usageLimit)
        assertEquals(3L, updated.perUserLimit)
        assertEquals("DISABLED", repository.disableCoupon(coupon.id, "admin", "corr").status)

        val duplicate = assertFailsWith<ApiException> {
            repository.createCoupon(CouponRequest(promotion.id, "SAVE-10"), "admin", "corr")
        }
        assertEquals(ErrorCode.CONFLICT, duplicate.errorCode)
        assertEquals(404, assertFailsWith<ApiException> {
            repository.createCoupon(CouponRequest("missing", "OTHER"), "admin", "corr")
        }.statusCode)
        assertEquals(404, assertFailsWith<ApiException> {
            repository.updateCoupon("missing", CouponUpdateRequest("ACTIVE"), "admin", "corr")
        }.statusCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.updateCoupon(coupon.id, CouponUpdateRequest("UNKNOWN"), "admin", "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.updateCoupon(coupon.id, CouponUpdateRequest("ACTIVE", usageLimit = -1), "admin", "corr")
        }.errorCode)
        assertEquals(ErrorCode.VALIDATION_ERROR, assertFailsWith<ApiException> {
            repository.updateCoupon(coupon.id, CouponUpdateRequest("ACTIVE", perUserLimit = -1), "admin", "corr")
        }.errorCode)
    }

    @Test
    fun `apply is idempotent enforces limits and transitions redemption with ownership`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val promotion = repository.create(
            PromotionRequest(
                "Limited", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, now.toString(),
                percentageBps = 1_000, usageLimit = 3, perUserLimit = 1,
            ), "admin", "corr",
        )
        val coupon = repository.createCoupon(CouponRequest(promotion.id, "LIMIT", usageLimit = 2), "admin", "corr")
        val request = PromotionCalculateRequest(lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 1_000)), couponCode = coupon.code)

        val first = repository.apply("user-1", request, "key-1", "order-1", "corr")
        assertEquals(RedemptionStatus.RESERVED, first.status)
        assertEquals(100L, first.discountMinor)
        assertEquals(first, repository.apply("user-1", request, "key-1", "order-1", "corr"))

        val changedRequest = request.copy(lines = listOf(PromotionLine("p", "v", quantity = 2, unitPriceMinor = 1_000)))
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.apply("user-1", changedRequest, "key-1", "order-1", "corr")
        }.errorCode)

        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<ApiException> {
            repository.transition("other-user", first.id, RedemptionStatus.COMMITTED, "corr")
        }.errorCode)
        val committed = repository.transition("user-1", first.id, RedemptionStatus.COMMITTED, "corr")
        assertEquals(RedemptionStatus.COMMITTED, committed.status)
        assertEquals(committed, repository.transition("user-1", first.id, RedemptionStatus.COMMITTED, "corr"))
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.transition("user-1", first.id, RedemptionStatus.RELEASED, "corr")
        }.errorCode)

        val second = repository.apply("user-2", request, "key-2", "order-2", "corr")
        assertEquals(RedemptionStatus.RESERVED, second.status)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.apply("user-3", request, "key-3", "order-3", "corr")
        }.errorCode)
        assertEquals(2L, database.coupons.values.single().usageCount)

        val perUserCoupon = repository.createCoupon(CouponRequest(promotion.id, "PERUSER", perUserLimit = 1), "admin", "corr")
        val perUserRequest = request.copy(couponCode = perUserCoupon.code)
        repository.apply("user-4", perUserRequest, "key-4", "order-4", "corr")
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.apply("user-4", perUserRequest, "key-5", "order-5", "corr")
        }.errorCode)

        val cancelPromotion = repository.create(
            PromotionRequest("Cancel", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, now.toString(), percentageBps = 1_000),
            "admin", "corr",
        )
        val cancelCoupon = repository.createCoupon(CouponRequest(cancelPromotion.id, "CANCEL"), "admin", "corr")
        val cancellable = repository.apply("user-cancel", request.copy(promotionId = cancelPromotion.id, couponCode = cancelCoupon.code), "key-cancel", "order-cancel", "corr")
        assertEquals(RedemptionStatus.CANCELLED, repository.transition("user-cancel", cancellable.id, RedemptionStatus.CANCELLED, "corr").status)
    }

    @Test
    fun `coupon persistence preserves unexpected database failures`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val promotion = createActive(repository)
        database.couponInsertFailure = SQLException("database unavailable", "08001")

        val failure = assertFailsWith<SQLException> {
            repository.createCoupon(CouponRequest(promotion.id, "UNAVAILABLE"), "admin", "corr")
        }

        assertEquals("08001", failure.sqlState)
        assertTrue(database.coupons.isEmpty())
    }

    @Test
    fun `release decrements usage and transaction rollback preserves state after coupon exhaustion`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val promotion = repository.create(
            PromotionRequest("Release", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, now.toString(), percentageBps = 500),
            "admin", "corr",
        )
        val coupon = repository.createCoupon(CouponRequest(promotion.id, "ONE", usageLimit = 1), "admin", "corr")
        val request = PromotionCalculateRequest(lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 1_000)), couponCode = coupon.code)
        val redemption = repository.apply("user-1", request, "key-1", null, "corr")
        assertEquals(coupon.id, database.redemptions[redemption.id]!!.couponId)
        assertEquals(RedemptionStatus.RELEASED, repository.transition("user-1", redemption.id, RedemptionStatus.RELEASED, "corr").status)
        assertEquals(0L, database.coupons.values.single().usageCount)
        assertEquals(0L, database.promotions[promotion.id]!!.response.usageCount)

        repository.apply("user-2", request, "key-2", null, "corr")
        val exhausted = assertFailsWith<ApiException> {
            repository.apply("user-3", request, "key-3", null, "corr")
        }
        assertEquals(ErrorCode.CONFLICT, exhausted.errorCode)
        assertEquals(1L, database.coupons.values.single().usageCount)
        assertEquals(1L, database.promotions[promotion.id]!!.response.usageCount)

        val noCouponPromotion = createActive(repository)
        val noCouponRequest = PromotionCalculateRequest(
            lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 1_000)),
            promotionId = noCouponPromotion.id,
        )
        val noCoupon = repository.apply("user-4", noCouponRequest, "key-4", null, "corr")
        assertEquals(RedemptionStatus.RELEASED, repository.transition("user-4", noCoupon.id, RedemptionStatus.RELEASED, "corr").status)
    }

    @Test
    fun `promotion persistence covers optional bindings missing rows and seller misses`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val input = PromotionRequest(
            name = "All fields",
            type = PromotionType.BUY_X_GET_Y,
            status = PromotionStatus.ACTIVE,
            startAt = now.toString(),
            endAt = now.plusSeconds(3_600).toString(),
            currency = "usd",
            minOrderMinor = 100,
            maxDiscountMinor = 500,
            percentageBps = 1_000,
            fixedAmountMinor = 50,
            buyQuantity = 2,
            getQuantity = 1,
            productIds = listOf("product-1"),
            categoryIds = listOf("category-1"),
            sellerIds = listOf("seller-1"),
            usageLimit = 10,
            perUserLimit = 2,
            stackPolicy = StackPolicy.PRIORITY_BASED,
            priority = 4,
            customerSegments = listOf("VIP"),
        )
        val created = repository.create(input, "admin", "corr")
        assertEquals("USD", created.currency)
        assertEquals(listOf(created), repository.listForSeller("seller-1", 1))
        assertTrue(repository.listForSeller("seller-missing", 1).isEmpty())

        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> {
            repository.update("missing", input, "admin", "corr")
        }.errorCode)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> {
            repository.archive("missing", "admin", "corr")
        }.errorCode)
    }

    @Test
    fun `promotion update binds every optional field and preserves the updated contract`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val created = createActive(repository)

        val updated = repository.update(
            created.id,
            PromotionRequest(
                name = "Complete update",
                type = PromotionType.BUY_X_GET_Y,
                status = PromotionStatus.ACTIVE,
                startAt = now.toString(),
                endAt = now.plusSeconds(3_600).toString(),
                currency = "USD",
                minOrderMinor = 500,
                maxDiscountMinor = 700,
                percentageBps = 1_500,
                fixedAmountMinor = 250,
                buyQuantity = 2,
                getQuantity = 1,
                productIds = listOf("product-1"),
                categoryIds = listOf("category-1"),
                sellerIds = listOf("seller-1"),
                customerSegments = listOf("VIP"),
                usageLimit = 4,
                perUserLimit = 2,
                stackPolicy = StackPolicy.PRIORITY_BASED,
                priority = 9,
            ),
            "admin",
            "corr",
        )

        assertEquals("Complete update", updated.name)
        assertEquals(PromotionType.BUY_X_GET_Y, updated.type)
        assertEquals("USD", updated.currency)
        assertEquals(700, updated.maxDiscountMinor)
        assertEquals(1_500, updated.percentageBps)
        assertEquals(250, updated.fixedAmountMinor)
        assertEquals(listOf("product-1"), updated.productIds)
        assertEquals(listOf("VIP"), updated.customerSegments)
        assertEquals(4, updated.usageLimit)
        assertEquals(2, updated.perUserLimit)
        assertEquals(StackPolicy.PRIORITY_BASED, updated.stackPolicy)
        assertEquals(9, updated.priority)
    }

    @Test
    fun `promotion usage limit is enforced after the first redemption`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val promotion = repository.create(
            PromotionRequest("One use", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, now.toString(), percentageBps = 500, usageLimit = 1),
            "admin",
            "corr",
        )
        val request = PromotionCalculateRequest(
            lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 1_000)),
            promotionId = promotion.id,
        )

        repository.apply("user-1", request, "key-1", "order-1", "corr")
        val exhausted = assertFailsWith<ApiException> {
            repository.apply("user-2", request, "key-2", "order-2", "corr")
        }

        assertEquals(ErrorCode.CONFLICT, exhausted.errorCode)
        assertEquals(1L, database.promotions[promotion.id]!!.response.usageCount)
    }

    @Test
    fun `promotion application covers no coupon per user limit and missing selection`() {
        val database = MemoryPromotionDatabase()
        val repository = PromotionRepository(database.dataSource())
        val promotion = repository.create(
            PromotionRequest("Per user", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, now.toString(), percentageBps = 500, perUserLimit = 1),
            "admin", "corr",
        )
        val request = PromotionCalculateRequest(lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 1_000)), promotionId = promotion.id)
        val redemption = repository.apply("user-1", request, "first-key", null, "corr")
        assertEquals(null, redemption.couponCode)
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.apply("user-1", request, "second-key", null, "corr")
        }.errorCode)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> {
            repository.apply("user-1", request.copy(promotionId = "missing"), "missing-key", null, "corr")
        }.errorCode)

        val paused = repository.create(
            PromotionRequest("Paused", PromotionType.PERCENTAGE, PromotionStatus.PAUSED, now.toString(), percentageBps = 500),
            "admin", "corr",
        )
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<ApiException> {
            repository.apply("user-2", request.copy(promotionId = paused.id), "paused-key", null, "corr")
        }.errorCode)
        assertEquals(RedemptionStatus.CANCELLED, repository.transition("user-1", redemption.id, RedemptionStatus.CANCELLED, "corr").status)
        assertEquals(redemption.id, repository.transition("user-1", redemption.id, RedemptionStatus.CANCELLED, "corr").id)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<ApiException> {
            repository.transition("user-1", "missing", RedemptionStatus.COMMITTED, "corr")
        }.errorCode)
    }

    private fun createActive(repository: PromotionRepository) = repository.create(
        PromotionRequest("Sale", PromotionType.PERCENTAGE, PromotionStatus.ACTIVE, now.toString(), percentageBps = 1_000),
        "admin", "corr",
    )
}

private class MemoryPromotionDatabase {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }
    data class PromotionRow(var response: PromotionResponse, var buyQuantity: Int?, var getQuantity: Int?)
    data class CouponRow(val id: String, val promotionId: String, var code: String, var status: String, var usageLimit: Long?, var usageCount: Long, var perUserLimit: Long?)
    data class RedemptionRow(var response: RedemptionResponse, val promotionId: String, val couponId: String?, val requestHash: String, val idempotencyKey: String)
    data class OutboxRow(val id: String, val aggregateId: String, val eventType: String, val occurredAt: Timestamp, val correlationId: String, val payload: String, var published: Boolean = false)
    data class Snapshot(val promotions: MutableMap<String, PromotionRow>, val coupons: MutableMap<String, CouponRow>, val redemptions: MutableMap<String, RedemptionRow>, val outbox: MutableMap<String, OutboxRow>)

    val promotions = linkedMapOf<String, PromotionRow>()
    val coupons = linkedMapOf<String, CouponRow>()
    val redemptions = linkedMapOf<String, RedemptionRow>()
    val outbox = linkedMapOf<String, OutboxRow>()
    var commits = 0
    var couponInsertFailure: SQLException? = null
    var orphanSellerRows = false

    fun dataSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
        if (method.name == "getConnection") connection() else defaultValue(method.returnType)
    }) as DataSource

    private fun connection(): Connection {
        var snapshot: Snapshot? = null
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                "createArrayOf" -> sqlArray(args?.getOrNull(1))
                "setAutoCommit" -> if (args?.firstOrNull() == false) snapshot = snapshot()
                "commit" -> { commits++; snapshot = null }
                "rollback" -> snapshot?.let { restore(it); snapshot = null }
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as Connection
    }

    private fun statement(rawSql: String): PreparedStatement {
        val sql = rawSql.replace(Regex("\\s+"), " ").trim().uppercase()
        val parameters = mutableMapOf<Int, Any?>()
        return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
            when {
                method.name.startsWith("set") && args?.firstOrNull() is Int -> { parameters[args[0] as Int] = args.getOrNull(1); null }
                method.name == "executeQuery" -> query(sql, parameters)
                method.name == "executeUpdate" -> update(sql, parameters)
                method.name == "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as PreparedStatement
    }

    private fun query(sql: String, p: Map<Int, Any?>): ResultSet {
        val rows = when {
            sql.startsWith("SELECT ID FROM PROMOTIONS WHERE SELLER_IDS") -> if (orphanSellerRows && p[1].toString().contains("orphan")) listOf(mapOf("id" to "missing-promotion")) else promotions.values.filter { it.response.sellerIds.contains(Json.decodeFromString<List<String>>(p[1].toString()).single()) }.map { mapOf("id" to it.response.id) }
            sql.startsWith("SELECT REQUEST_HASH,RESPONSE_JSON") -> redemptions.values.filter { it.response.userId == p[1] && it.idempotencyKey == p[2] }.map { mapOf("request_hash" to it.requestHash, "response_json" to json.encodeToString(it.response)) }
            sql.startsWith("SELECT COUNT(*) FROM PROMOTION_REDEMPTIONS") -> listOf(mapOf("count" to redemptions.values.count { it.response.userId == p[1] && it.promotionId == p[2] && it.response.status in setOf(RedemptionStatus.RESERVED, RedemptionStatus.COMMITTED) }.toLong()))
            sql.startsWith("SELECT ID,PROMOTION_ID,CODE,STATUS") -> coupons[p[1]]?.let { listOf(couponMap(it)) } ?: emptyList()
            sql.startsWith("SELECT PROMOTION_ID,COUPON_ID,RESPONSE_JSON") -> redemptions[p[1]]?.let { listOf(mapOf("promotion_id" to it.promotionId, "coupon_id" to it.couponId, "response_json" to json.encodeToString(it.response))) } ?: emptyList()
            sql.startsWith("SELECT ID,AGGREGATE_ID,EVENT_TYPE") -> outbox.values.filterNot { it.published }.take((p[1] as? Int) ?: 100).map { mapOf("id" to it.id, "aggregate_id" to it.aggregateId, "event_type" to it.eventType, "schema_version" to 1, "occurred_at" to it.occurredAt, "correlation_id" to it.correlationId, "payload_json" to it.payload) }
            sql.contains("JOIN COUPONS") -> coupons.values.firstOrNull { it.code.uppercase() == p[1].toString().uppercase() }?.let { coupon -> promotions[coupon.promotionId]?.let { listOf(promotionMap(it, coupon)) } } ?: emptyList()
            sql.startsWith("SELECT * FROM PROMOTIONS WHERE ID") -> promotions[p[1]]?.let { listOf(promotionMap(it)) } ?: emptyList()
            else -> emptyList()
        }
        return resultSet(rows)
    }

    private fun update(sql: String, p: Map<Int, Any?>): Int = when {
        sql.startsWith("INSERT INTO PROMOTIONS") -> { val id = p[1].toString(); promotions[id] = PromotionRow(responseFrom(p, id), p[12] as? Int, p[13] as? Int); 1 }
        sql.startsWith("UPDATE PROMOTIONS SET STATUS='ARCHIVED'") -> promotions[p[2]]?.let { it.response = it.response.copy(status = PromotionStatus.ARCHIVED, version = it.response.version + 1); 1 } ?: 0
        sql.startsWith("UPDATE PROMOTIONS SET NAME=") -> promotions[p[22]]?.let { old -> old.response = updateResponseFrom(p, p[22].toString()).copy(usageCount = old.response.usageCount, version = old.response.version + 1); old.buyQuantity = p[11] as? Int; old.getQuantity = p[12] as? Int; 1 } ?: 0
        sql.startsWith("INSERT INTO COUPONS") -> { couponInsertFailure?.let { throw it }; val normalized = p[4].toString(); if (coupons.values.any { it.code.uppercase() == normalized }) throw SQLException("duplicate", "23505"); coupons[p[1].toString()] = CouponRow(p[1].toString(), p[2].toString(), p[3].toString(), "ACTIVE", p[5] as? Long, 0, p[6] as? Long); 1 }
        sql.startsWith("UPDATE COUPONS SET STATUS=") -> coupons[p[4]]?.let { it.status = p[1].toString(); it.usageLimit = p[2] as? Long; it.perUserLimit = p[3] as? Long; 1 } ?: 0
        sql.startsWith("UPDATE PROMOTIONS SET USAGE_COUNT") && !sql.startsWith("UPDATE PROMOTIONS SET USAGE_COUNT=GREATEST") -> promotions[p[1]]?.let { if (it.response.usageLimit != null && it.response.usageCount >= it.response.usageLimit!!) 0 else { it.response = it.response.copy(usageCount = it.response.usageCount + 1); 1 } } ?: 0
        sql.startsWith("UPDATE COUPONS SET USAGE_COUNT") && !sql.startsWith("UPDATE COUPONS SET USAGE_COUNT=GREATEST") -> coupons[p[1]]?.let { if (it.status != "ACTIVE" || it.usageLimit != null && it.usageCount >= it.usageLimit!!) 0 else { it.usageCount++; 1 } } ?: 0
        sql.startsWith("UPDATE PROMOTIONS SET USAGE_COUNT=GREATEST") -> promotions[p[1]]?.let { it.response = it.response.copy(usageCount = (it.response.usageCount - 1).coerceAtLeast(0)); 1 } ?: 0
        sql.startsWith("UPDATE COUPONS SET USAGE_COUNT=GREATEST") -> coupons[p[1]]?.let { it.usageCount = (it.usageCount - 1).coerceAtLeast(0); 1 } ?: 0
        sql.startsWith("INSERT INTO PROMOTION_REDEMPTIONS") -> { val response = json.decodeFromString<RedemptionResponse>(p[10].toString()); redemptions[p[1].toString()] = RedemptionRow(response, p[2].toString(), p[3] as? String, p[7].toString(), p[6].toString()); 1 }
        sql.startsWith("UPDATE PROMOTION_REDEMPTIONS SET STATUS=") -> redemptions[p[3]]?.let { it.response = it.response.copy(status = RedemptionStatus.valueOf(p[1].toString())); 1 } ?: 0
        sql.startsWith("INSERT INTO PROMOTION_OUTBOX_EVENTS") -> { val id = p[1].toString(); outbox[id] = OutboxRow(id, p[2].toString(), p[3].toString(), p[4] as Timestamp, p[5].toString(), p[6].toString()); 1 }
        sql.startsWith("UPDATE PROMOTION_OUTBOX_EVENTS") -> { val ids = (p[2] as Array).getArray() as kotlin.Array<*>; ids.forEach { id -> outbox[id.toString()]?.published = true }; 1 }
        else -> 1
    }

    private fun responseFrom(p: Map<Int, Any?>, id: String) = PromotionResponse(
        id, p[2].toString(), PromotionType.valueOf(p[3].toString()), PromotionStatus.valueOf(p[4].toString()),
        (p[5] as Timestamp).toInstant().toString(), (p[6] as? Timestamp)?.toInstant()?.toString(), p[7].toString(),
        (p[8] as Number).toLong(), p[9] as? Long, p[10] as? Int, p[11] as? Long,
        Json.decodeFromString(p[14].toString()), Json.decodeFromString(p[15].toString()), Json.decodeFromString(p[16].toString()),
        p[18] as? Long, 0, p[19] as? Long, StackPolicy.valueOf(p[20].toString()), (p[21] as Number).toInt(), 1,
        Json.decodeFromString(p[17].toString()),
    )

    private fun updateResponseFrom(p: Map<Int, Any?>, id: String) = PromotionResponse(
        id, p[1].toString(), PromotionType.valueOf(p[2].toString()), PromotionStatus.valueOf(p[3].toString()),
        (p[4] as Timestamp).toInstant().toString(), (p[5] as? Timestamp)?.toInstant()?.toString(), p[6].toString(),
        (p[7] as Number).toLong(), p[8] as? Long, p[9] as? Int, p[10] as? Long,
        Json.decodeFromString(p[13].toString()), Json.decodeFromString(p[14].toString()), Json.decodeFromString(p[15].toString()),
        p[17] as? Long, 0, p[18] as? Long, StackPolicy.valueOf(p[19].toString()), (p[20] as Number).toInt(), 1,
        Json.decodeFromString(p[16].toString()),
    )

    private fun promotionMap(row: PromotionRow, coupon: CouponRow? = null): Map<String, Any?> = buildMap {
        val p = row.response
        put("id", p.id); put("name", p.name); put("type", p.type.name); put("status", p.status.name); put("start_at", Timestamp.from(Instant.parse(p.startAt))); put("end_at", p.endAt?.let { Timestamp.from(Instant.parse(it)) }); put("currency", p.currency); put("min_order_minor", p.minOrderMinor); put("max_discount_minor", p.maxDiscountMinor); put("percentage_bps", p.percentageBps); put("fixed_amount_minor", p.fixedAmountMinor); put("buy_quantity", row.buyQuantity); put("get_quantity", row.getQuantity); put("product_ids", json.encodeToString(p.productIds)); put("category_ids", json.encodeToString(p.categoryIds)); put("seller_ids", json.encodeToString(p.sellerIds)); put("customer_segments", json.encodeToString(p.customerSegments)); put("usage_limit", p.usageLimit); put("usage_count", p.usageCount); put("per_user_limit", p.perUserLimit); put("stack_policy", p.stackPolicy.name); put("priority", p.priority); put("version", p.version)
        if (coupon != null) { put("coupon_id", coupon.id); put("coupon_code", coupon.code); put("coupon_usage_limit", coupon.usageLimit); put("coupon_usage_count", coupon.usageCount); put("coupon_per_user_limit", coupon.perUserLimit) }
    }

    private fun couponMap(coupon: CouponRow) = mapOf<String, Any?>("id" to coupon.id, "promotion_id" to coupon.promotionId, "code" to coupon.code, "status" to coupon.status, "usage_limit" to coupon.usageLimit, "usage_count" to coupon.usageCount, "per_user_limit" to coupon.perUserLimit)

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        var wasNull = false
        fun value(key: Any): Any? {
            val row = rows.getOrNull(index)
            val normalized = key.toString()
            val actualKey = normalized.toIntOrNull()?.let { row?.keys?.elementAtOrNull(it - 1) } ?: normalized
            val value = row?.get(actualKey)
            wasNull = value == null
            return value
        }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
            when (method.name) {
                "next" -> ++index < rows.size
                "getString" -> value(args?.firstOrNull() ?: "")?.toString()
                "getInt" -> (value(args?.firstOrNull() ?: "") as? Number)?.toInt() ?: 0
                "getLong" -> (value(args?.firstOrNull() ?: "") as? Number)?.toLong() ?: 0L
                "getTimestamp" -> value(args?.firstOrNull() ?: "") as? Timestamp
                "wasNull" -> wasNull
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as ResultSet
    }

    private fun sqlArray(values: Any?): Array = Proxy.newProxyInstance(Array::class.java.classLoader, arrayOf(Array::class.java), InvocationHandler { _, method, _ -> if (method.name == "getArray") values else defaultValue(method.returnType) }) as Array

    private fun snapshot() = Snapshot(promotions.mapValuesTo(linkedMapOf()) { (_, v) -> v.copy(response = v.response.copy()) }, coupons.mapValuesTo(linkedMapOf()) { (_, v) -> v.copy() }, redemptions.mapValuesTo(linkedMapOf()) { (_, v) -> v.copy(response = v.response.copy()) }, outbox.mapValuesTo(linkedMapOf()) { (_, v) -> v.copy() })
    private fun restore(snapshot: Snapshot) { promotions.clear(); promotions.putAll(snapshot.promotions); coupons.clear(); coupons.putAll(snapshot.coupons); redemptions.clear(); redemptions.putAll(snapshot.redemptions); outbox.clear(); outbox.putAll(snapshot.outbox) }

    private fun defaultValue(type: Class<*>): Any? = when (type) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
}
