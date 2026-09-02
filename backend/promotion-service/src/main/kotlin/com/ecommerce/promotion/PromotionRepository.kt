package com.ecommerce.promotion

import com.ecommerce.platform.common.CommerceId
import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import com.ecommerce.platform.service.ServiceOutboxRecord
import com.ecommerce.platform.service.ServiceOutboxStore
import com.ecommerce.platform.service.newOutboxId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

private data class PromotionRecord(val response: PromotionResponse, val buyQuantity: Int?, val getQuantity: Int?)
private data class CouponRecord(val id: String, val code: String, val usageLimit: Long?, val usageCount: Long, val perUserLimit: Long?)
private data class SelectedPromotion(val promotion: PromotionRecord, val coupon: CouponRecord?)
private data class StoredRedemption(val response: RedemptionResponse, val promotionId: String, val couponId: String?)

class PromotionRepository(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : ServiceOutboxStore, PromotionStore {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    override fun listForSeller(sellerId: String, limit: Int): List<PromotionResponse> = withConnection { connection ->
        connection.prepareStatement("SELECT id FROM promotions WHERE seller_ids @> ?::jsonb ORDER BY updated_at DESC LIMIT ?").use { statement ->
            statement.setString(1, json.encodeToString(listOf(sellerId)))
            statement.setInt(2, limit.coerceIn(1, 100))
            statement.executeQuery().use { result ->
                buildList { while (result.next()) select(connection, result.getString(1), false)?.promotion?.response?.let(::add) }
            }
        }
    }

    override fun create(input: PromotionRequest, actorId: String, correlationId: String): PromotionResponse = transaction { connection ->
        validate(input)
        val now = Instant.now(); val id = CommerceId.new("promo").value
        connection.prepareStatement("INSERT INTO promotions(id,name,type,status,start_at,end_at,currency,min_order_minor,max_discount_minor,percentage_bps,fixed_amount_minor,buy_quantity,get_quantity,product_ids,category_ids,seller_ids,customer_segments,usage_limit,per_user_limit,stack_policy,priority,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?,?)").use { statement -> bindPromotion(statement, id, input, actorId, now); statement.executeUpdate() }
        val response = select(connection, id, false)!!.promotion.response
        outbox(connection, id, "PromotionCreated", response, correlationId, now)
        response
    }

    override fun update(id: String, input: PromotionRequest, actorId: String, correlationId: String): PromotionResponse = transaction { connection ->
        validate(input); if (select(connection, id, true) == null) throw ApiException(ErrorCode.NOT_FOUND, "Promotion not found.", 404)
        val now = Instant.now()
        connection.prepareStatement("UPDATE promotions SET name=?,type=?,status=?,start_at=?,end_at=?,currency=?,min_order_minor=?,max_discount_minor=?,percentage_bps=?,fixed_amount_minor=?,buy_quantity=?,get_quantity=?,product_ids=?::jsonb,category_ids=?::jsonb,seller_ids=?::jsonb,customer_segments=?::jsonb,usage_limit=?,per_user_limit=?,stack_policy=?,priority=?,version=version+1,updated_at=? WHERE id=?").use { statement ->
            statement.setString(1, input.name.trim()); statement.setString(2, input.type.name); statement.setString(3, input.status.name); statement.setTimestamp(4, Timestamp.from(Instant.parse(input.startAt))); input.endAt?.let { statement.setTimestamp(5, Timestamp.from(Instant.parse(it))) } ?: statement.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE); statement.setString(6, input.currency.uppercase()); statement.setLong(7, input.minOrderMinor); input.maxDiscountMinor?.let { statement.setLong(8, it) } ?: statement.setNull(8, java.sql.Types.BIGINT); input.percentageBps?.let { statement.setInt(9, it) } ?: statement.setNull(9, java.sql.Types.INTEGER); input.fixedAmountMinor?.let { statement.setLong(10, it) } ?: statement.setNull(10, java.sql.Types.BIGINT); input.buyQuantity?.let { statement.setInt(11, it) } ?: statement.setNull(11, java.sql.Types.INTEGER); input.getQuantity?.let { statement.setInt(12, it) } ?: statement.setNull(12, java.sql.Types.INTEGER); statement.setString(13, json.encodeToString(input.productIds)); statement.setString(14, json.encodeToString(input.categoryIds)); statement.setString(15, json.encodeToString(input.sellerIds)); statement.setString(16, json.encodeToString(input.customerSegments)); input.usageLimit?.let { statement.setLong(17, it) } ?: statement.setNull(17, java.sql.Types.BIGINT); input.perUserLimit?.let { statement.setLong(18, it) } ?: statement.setNull(18, java.sql.Types.BIGINT); statement.setString(19, input.stackPolicy.name); statement.setInt(20, input.priority); statement.setTimestamp(21, now.timestamp()); statement.setString(22, id); statement.executeUpdate()
        }
        val response = select(connection, id, false)!!.promotion.response; outbox(connection, id, "PromotionUpdated", response, correlationId, now); response
    }

    override fun archive(id: String, actorId: String, correlationId: String): PromotionResponse = transaction { connection ->
        val now = Instant.now(); connection.prepareStatement("UPDATE promotions SET status='ARCHIVED',version=version+1,updated_at=? WHERE id=?").use { statement -> statement.setTimestamp(1, now.timestamp()); statement.setString(2, id); if (statement.executeUpdate() != 1) throw ApiException(ErrorCode.NOT_FOUND, "Promotion not found.", 404) }; val response = select(connection, id, false)!!.promotion.response; outbox(connection, id, "PromotionArchived", response, correlationId, now); response
    }

    override fun createCoupon(input: CouponRequest, actorId: String, correlationId: String): CouponResponse = transaction { connection ->
        if (input.code.trim().length !in 3..64 || input.usageLimit != null && input.usageLimit < 0 || input.perUserLimit != null && input.perUserLimit < 0) throw ApiException(ErrorCode.VALIDATION_ERROR, "Coupon definition is invalid.", 400)
        if (select(connection, input.promotionId, true) == null) throw ApiException(ErrorCode.NOT_FOUND, "Promotion not found.", 404)
        val now = Instant.now(); val id = CommerceId.new("coupon").value; val normalized = input.code.trim().uppercase()
        try { connection.prepareStatement("INSERT INTO coupons(id,promotion_id,code,normalized_code,usage_limit,per_user_limit,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)").use { statement -> statement.setString(1, id); statement.setString(2, input.promotionId); statement.setString(3, input.code.trim()); statement.setString(4, normalized); input.usageLimit?.let { statement.setLong(5, it) } ?: statement.setNull(5, java.sql.Types.BIGINT); input.perUserLimit?.let { statement.setLong(6, it) } ?: statement.setNull(6, java.sql.Types.BIGINT); statement.setTimestamp(7, now.timestamp()); statement.setTimestamp(8, now.timestamp()); statement.executeUpdate() } } catch (error: SQLException) { if (error.sqlState == "23505") throw ApiException(ErrorCode.CONFLICT, "Coupon code already exists.", 409); throw error }
        val response = CouponResponse(id, input.promotionId, input.code.trim(), "ACTIVE", input.usageLimit, 0, input.perUserLimit); outbox(connection, id, "CouponCreated", response, correlationId, now); response
    }

    override fun updateCoupon(id: String, input: CouponUpdateRequest, actorId: String, correlationId: String): CouponResponse = transaction { connection ->
        if (input.status !in setOf("ACTIVE", "DISABLED", "EXPIRED") || input.usageLimit != null && input.usageLimit < 0 || input.perUserLimit != null && input.perUserLimit < 0) throw ApiException(ErrorCode.VALIDATION_ERROR, "Coupon update is invalid.", 400)
        connection.prepareStatement("UPDATE coupons SET status=?,usage_limit=?,per_user_limit=?,updated_at=now() WHERE id=?").use { statement -> statement.setString(1, input.status); input.usageLimit?.let { statement.setLong(2, it) } ?: statement.setNull(2, java.sql.Types.BIGINT); input.perUserLimit?.let { statement.setLong(3, it) } ?: statement.setNull(3, java.sql.Types.BIGINT); statement.setString(4, id); if (statement.executeUpdate() != 1) throw ApiException(ErrorCode.NOT_FOUND, "Coupon not found.", 404) }
        val response = coupon(connection, id)!!; outbox(connection, id, "CouponUpdated", response, correlationId, Instant.now()); response
    }

    override fun disableCoupon(id: String, actorId: String, correlationId: String): CouponResponse = updateCoupon(id, CouponUpdateRequest("DISABLED"), actorId, correlationId)

    override fun calculate(userId: String?, input: PromotionCalculateRequest): PromotionQuote = withConnection { connection ->
        validateRequest(input); val selected = select(connection, input.promotionId, false, input.couponCode?.trim()?.uppercase()) ?: return@withConnection PromotionQuote(null, input.couponCode, input.currency.uppercase(), 0, false, reason = "PROMOTION_NOT_FOUND")
        calculate(selected, input)
    }

    override fun apply(userId: String, input: PromotionCalculateRequest, key: String, orderId: String?, correlationId: String): RedemptionResponse = transaction { connection ->
        if (key.isBlank() || key.length > 128) throw ApiException(ErrorCode.VALIDATION_ERROR, "A valid Idempotency-Key is required.", 400)
        validateRequest(input); val hash = hash(json.encodeToString(input) + "|" + orderId.orEmpty())
        val prior = connection.prepareStatement("SELECT request_hash,response_json::text FROM promotion_redemptions WHERE user_id=? AND idempotency_key=? FOR UPDATE").use { statement -> statement.setString(1, userId); statement.setString(2, key); statement.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null } }
        if (prior != null) { if (prior.first != hash) throw ApiException(ErrorCode.CONFLICT, "Idempotency key was reused with a different request.", 409); return@transaction json.decodeFromString(prior.second) }
        val selected = select(connection, input.promotionId, true, input.couponCode?.trim()?.uppercase()) ?: throw ApiException(ErrorCode.NOT_FOUND, "Promotion or coupon not found.", 404)
        val quote = calculate(selected, input); if (quote.reason != null) throw ApiException(ErrorCode.CONFLICT, quote.reason, 409)
        val promotion = selected.promotion.response; val usedByUser = connection.prepareStatement("SELECT count(*) FROM promotion_redemptions WHERE user_id=? AND promotion_id=? AND status IN ('RESERVED','COMMITTED')").use { statement -> statement.setString(1, userId); statement.setString(2, promotion.id); statement.executeQuery().use { it.next(); it.getLong(1) } }
        val userLimit = selected.coupon?.perUserLimit ?: promotion.perUserLimit; if (userLimit != null && usedByUser >= userLimit) throw ApiException(ErrorCode.CONFLICT, "Promotion usage limit per customer has been reached.", 409)
        incrementPromotion(connection, promotion)
        selected.coupon?.let { incrementCoupon(connection, it) }
        val now = Instant.now(); val id = CommerceId.new("red").value; val response = RedemptionResponse(id, promotion.id, selected.coupon?.code, userId, orderId, quote.discountMinor, quote.currency, RedemptionStatus.RESERVED, quote, now.toString())
        connection.prepareStatement("INSERT INTO promotion_redemptions(id,promotion_id,coupon_id,user_id,order_id,idempotency_key,request_hash,discount_minor,currency,status,response_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,'RESERVED',?::jsonb,?,?)").use { statement -> statement.setString(1, id); statement.setString(2, promotion.id); statement.setString(3, selected.coupon?.id); statement.setString(4, userId); statement.setString(5, orderId); statement.setString(6, key); statement.setString(7, hash); statement.setLong(8, quote.discountMinor); statement.setString(9, quote.currency); statement.setString(10, json.encodeToString(response)); statement.setTimestamp(11, now.timestamp()); statement.setTimestamp(12, now.timestamp()); statement.executeUpdate() }
        outbox(connection, id, "PromotionRedeemed", response, correlationId, now); response
    }

    override fun transition(userId: String, id: String, target: RedemptionStatus, correlationId: String): RedemptionResponse = transaction { connection ->
        val existing = redemption(connection, id) ?: throw ApiException(ErrorCode.NOT_FOUND, "Redemption not found.", 404); if (existing.response.userId != userId) throw ApiException(ErrorCode.FORBIDDEN, "You do not own this redemption.", 403)
        if (existing.response.status == target) return@transaction existing.response
        if (existing.response.status != RedemptionStatus.RESERVED || target !in setOf(RedemptionStatus.COMMITTED, RedemptionStatus.RELEASED, RedemptionStatus.CANCELLED)) throw ApiException(ErrorCode.CONFLICT, "Redemption cannot transition from ${existing.response.status} to $target.", 409)
        val now = Instant.now(); if (target == RedemptionStatus.RELEASED) { decrement(connection, "promotions", existing.promotionId); existing.couponId?.let { decrement(connection, "coupons", it) } }
        connection.prepareStatement("UPDATE promotion_redemptions SET status=?,updated_at=? WHERE id=?").use { statement -> statement.setString(1, target.name); statement.setTimestamp(2, now.timestamp()); statement.setString(3, id); statement.executeUpdate() }
        val response = existing.response.copy(status = target); outbox(connection, id, "Promotion${target.name.replaceFirstChar { it.titlecase() }}", response, correlationId, now); response
    }

    private fun incrementPromotion(connection: Connection, promotion: PromotionResponse) { val updated = connection.prepareStatement("UPDATE promotions SET usage_count=usage_count+1 WHERE id=? AND (usage_limit IS NULL OR usage_count < usage_limit)").use { statement -> statement.setString(1, promotion.id); statement.executeUpdate() }; if (updated != 1) throw ApiException(ErrorCode.CONFLICT, "Promotion usage limit has been reached.", 409) }
    private fun incrementCoupon(connection: Connection, coupon: CouponRecord) { val updated = connection.prepareStatement("UPDATE coupons SET usage_count=usage_count+1,updated_at=now() WHERE id=? AND status='ACTIVE' AND (usage_limit IS NULL OR usage_count < usage_limit)").use { statement -> statement.setString(1, coupon.id); statement.executeUpdate() }; if (updated != 1) throw ApiException(ErrorCode.CONFLICT, "Coupon usage limit has been reached.", 409) }
    private fun decrement(connection: Connection, table: String, id: String) { connection.prepareStatement("UPDATE $table SET usage_count=GREATEST(usage_count-1,0),updated_at=now() WHERE id=?").use { statement -> statement.setString(1, id); statement.executeUpdate() } }

    private fun calculate(selected: SelectedPromotion, input: PromotionCalculateRequest): PromotionQuote {
        val promotion = selected.promotion; val p = promotion.response; val subtotal = input.lines.sumOf { it.lineTotalMinor }; if (p.currency != input.currency.uppercase()) return PromotionQuote(p.id, selected.coupon?.code, input.currency.uppercase(), 0, false, reason = "CURRENCY_NOT_SUPPORTED"); if (p.customerSegments.isNotEmpty() && input.customerSegment !in p.customerSegments) return PromotionQuote(p.id, selected.coupon?.code, p.currency, 0, false, reason = "CUSTOMER_SEGMENT_NOT_ELIGIBLE"); val now = clock.instant(); if (p.status != PromotionStatus.ACTIVE || now.isBefore(Instant.parse(p.startAt)) || p.endAt?.let { !now.isBefore(Instant.parse(it)) } == true) return PromotionQuote(p.id, selected.coupon?.code, p.currency, 0, false, reason = "PROMOTION_INACTIVE"); if (subtotal < p.minOrderMinor) return PromotionQuote(p.id, selected.coupon?.code, p.currency, 0, false, reason = "MINIMUM_ORDER_NOT_MET")
        val eligible = input.lines.filter { line -> eligible(p, line) }; if (eligible.isEmpty()) return PromotionQuote(p.id, selected.coupon?.code, p.currency, 0, false, reason = "NO_ELIGIBLE_ITEMS")
        val discounts = linkedMapOf<String, Long>(); var discount = when (p.type) { PromotionType.PERCENTAGE -> eligible.sumOf { percentageDiscountMinor(it.lineTotalMinor, p.percentageBps ?: 0) }; PromotionType.FIXED_AMOUNT -> p.fixedAmountMinor ?: 0; PromotionType.PRODUCT_DISCOUNT, PromotionType.CATEGORY_DISCOUNT -> eligible.sumOf { percentageDiscountMinor(it.lineTotalMinor, p.percentageBps ?: 0) }; PromotionType.BUY_X_GET_Y -> eligible.sumOf { line -> val group = (selected.promotion.buyQuantity ?: 0) + (selected.promotion.getQuantity ?: 0); if (group <= 0) 0 else ((line.quantity / group) * (selected.promotion.getQuantity ?: 0) + ((line.quantity % group) - (selected.promotion.buyQuantity ?: 0)).coerceAtLeast(0)) * line.unitPriceMinor }; PromotionType.FREE_SHIPPING -> 0 }
        if (p.type == PromotionType.PERCENTAGE || p.type == PromotionType.PRODUCT_DISCOUNT || p.type == PromotionType.CATEGORY_DISCOUNT) eligible.forEach { line -> discounts[line.variantId] = percentageDiscountMinor(line.lineTotalMinor, p.percentageBps ?: 0) }
        discount = discount.coerceAtMost(subtotal); p.maxDiscountMinor?.let { discount = discount.coerceAtMost(it) }
        return PromotionQuote(p.id, selected.coupon?.code, p.currency, discount, p.type == PromotionType.FREE_SHIPPING, discounts)
    }

    private fun eligible(p: PromotionResponse, line: PromotionLine): Boolean { val scoped = p.productIds.isNotEmpty() || p.categoryIds.isNotEmpty() || p.sellerIds.isNotEmpty(); return !scoped || p.productIds.contains(line.productId) || (line.categoryId != null && p.categoryIds.contains(line.categoryId)) || (line.sellerId != null && p.sellerIds.contains(line.sellerId)) }
    private fun validate(input: PromotionRequest) { if (input.name.isBlank() || input.startAt.isBlank() || input.minOrderMinor < 0 || input.maxDiscountMinor != null && input.maxDiscountMinor < 0 || input.percentageBps != null && input.percentageBps !in 1..10_000 || input.fixedAmountMinor != null && input.fixedAmountMinor <= 0 || input.usageLimit != null && input.usageLimit < 0 || input.perUserLimit != null && input.perUserLimit < 0 || input.buyQuantity != null && input.buyQuantity <= 0 || input.getQuantity != null && input.getQuantity <= 0) throw ApiException(ErrorCode.VALIDATION_ERROR, "Promotion definition is invalid.", 400); Instant.parse(input.startAt); input.endAt?.let(Instant::parse) }
    private fun validateRequest(input: PromotionCalculateRequest) { if (input.lines.isEmpty() || input.lines.any { it.quantity <= 0 || it.unitPriceMinor < 0 }) throw ApiException(ErrorCode.VALIDATION_ERROR, "Promotion cart lines are invalid.", 400) }

    private fun select(connection: Connection, promotionId: String?, lock: Boolean, couponCode: String? = null): SelectedPromotion? {
        val suffix = if (lock) " FOR UPDATE" else ""
        return if (!couponCode.isNullOrBlank()) connection.prepareStatement("SELECT p.*,c.id coupon_id,c.code coupon_code,c.usage_limit coupon_usage_limit,c.usage_count coupon_usage_count,c.per_user_limit coupon_per_user_limit FROM promotions p JOIN coupons c ON c.promotion_id=p.id WHERE c.normalized_code=?$suffix").use { statement -> statement.setString(1, couponCode); statement.executeQuery().use { if (it.next()) it.selected() else null } } else promotionId?.let { id -> connection.prepareStatement("SELECT * FROM promotions WHERE id=?$suffix").use { statement -> statement.setString(1, id); statement.executeQuery().use { if (it.next()) SelectedPromotion(it.promotion(), null) else null } } }
    }

    private fun redemption(connection: Connection, id: String): StoredRedemption? = connection.prepareStatement("SELECT promotion_id,coupon_id,response_json::text FROM promotion_redemptions WHERE id=? FOR UPDATE").use { statement -> statement.setString(1, id); statement.executeQuery().use { if (it.next()) StoredRedemption(json.decodeFromString(it.getString(3)), it.getString(1), it.getString(2)) else null } }
    private fun coupon(connection: Connection, id: String): CouponResponse? = connection.prepareStatement("SELECT id,promotion_id,code,status,usage_limit,usage_count,per_user_limit FROM coupons WHERE id=?").use { statement -> statement.setString(1, id); statement.executeQuery().use { if (!it.next()) null else CouponResponse(it.getString(1),it.getString(2),it.getString(3),it.getString(4),it.getLongOrNull("usage_limit"),it.getLong("usage_count"),it.getLongOrNull("per_user_limit")) } }
    private fun bindPromotion(statement: java.sql.PreparedStatement, id: String, input: PromotionRequest, actor: String, now: Instant) { statement.setString(1, id); statement.setString(2, input.name.trim()); statement.setString(3, input.type.name); statement.setString(4, input.status.name); statement.setTimestamp(5, Timestamp.from(Instant.parse(input.startAt))); input.endAt?.let { statement.setTimestamp(6, Timestamp.from(Instant.parse(it))) } ?: statement.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE); statement.setString(7, input.currency.uppercase()); statement.setLong(8, input.minOrderMinor); input.maxDiscountMinor?.let { statement.setLong(9, it) } ?: statement.setNull(9, java.sql.Types.BIGINT); input.percentageBps?.let { statement.setInt(10, it) } ?: statement.setNull(10, java.sql.Types.INTEGER); input.fixedAmountMinor?.let { statement.setLong(11, it) } ?: statement.setNull(11, java.sql.Types.BIGINT); input.buyQuantity?.let { statement.setInt(12, it) } ?: statement.setNull(12, java.sql.Types.INTEGER); input.getQuantity?.let { statement.setInt(13, it) } ?: statement.setNull(13, java.sql.Types.INTEGER); statement.setString(14, json.encodeToString(input.productIds)); statement.setString(15, json.encodeToString(input.categoryIds)); statement.setString(16, json.encodeToString(input.sellerIds)); statement.setString(17, json.encodeToString(input.customerSegments)); input.usageLimit?.let { statement.setLong(18, it) } ?: statement.setNull(18, java.sql.Types.BIGINT); input.perUserLimit?.let { statement.setLong(19, it) } ?: statement.setNull(19, java.sql.Types.BIGINT); statement.setString(20, input.stackPolicy.name); statement.setInt(21, input.priority); statement.setString(22, actor); statement.setTimestamp(23, now.timestamp()); statement.setTimestamp(24, now.timestamp()) }
    private fun ResultSet.promotion(): PromotionRecord { val productIds = json.decodeFromString<List<String>>(getString("product_ids")); val categoryIds = json.decodeFromString<List<String>>(getString("category_ids")); val sellerIds = json.decodeFromString<List<String>>(getString("seller_ids")); val customerSegments = json.decodeFromString<List<String>>(getString("customer_segments")); return PromotionRecord(PromotionResponse(getString("id"),getString("name"),PromotionType.valueOf(getString("type")),PromotionStatus.valueOf(getString("status")),getTimestamp("start_at").toInstant().toString(),getTimestamp("end_at")?.toInstant()?.toString(),getString("currency"),getLong("min_order_minor"),getLongOrNull("max_discount_minor"),getIntOrNull("percentage_bps"),getLongOrNull("fixed_amount_minor"),productIds,categoryIds,sellerIds,getLongOrNull("usage_limit"),getLong("usage_count"),getLongOrNull("per_user_limit"),StackPolicy.valueOf(getString("stack_policy")),getInt("priority"),getLong("version"),customerSegments),getIntOrNull("buy_quantity"),getIntOrNull("get_quantity")) }
    private fun ResultSet.selected() = SelectedPromotion(promotion(), CouponRecord(getString("coupon_id"),getString("coupon_code"),getLongOrNull("coupon_usage_limit"),getLong("coupon_usage_count"),getLongOrNull("coupon_per_user_limit")))
    private fun ResultSet.getLongOrNull(name: String): Long? = getLong(name).let { if (wasNull()) null else it }
    private fun ResultSet.getIntOrNull(name: String): Int? = getInt(name).let { if (wasNull()) null else it }
    private fun outbox(connection: Connection, id: String, type: String, payload: Any, correlationId: String, now: Instant) = connection.prepareStatement("INSERT INTO promotion_outbox_events(id,aggregate_id,event_type,occurred_at,correlation_id,payload_json) VALUES (?,?,?,?,?,?::jsonb)").use { statement -> statement.setString(1, newOutboxId()); statement.setString(2, id); statement.setString(3, type); statement.setTimestamp(4, now.timestamp()); statement.setString(5, correlationId); statement.setString(6, when (payload) { is PromotionResponse -> json.encodeToString(payload); is CouponResponse -> json.encodeToString(payload); is RedemptionResponse -> json.encodeToString(payload); else -> "{}" }); statement.executeUpdate() }
    override fun unpublished(limit: Int): List<ServiceOutboxRecord> = withConnection { connection -> connection.prepareStatement("SELECT id,aggregate_id,event_type,schema_version,occurred_at,correlation_id,payload_json::text FROM promotion_outbox_events WHERE published_at IS NULL ORDER BY occurred_at LIMIT ?").use { statement -> statement.setInt(1, limit); statement.executeQuery().use { result -> buildList { while (result.next()) add(ServiceOutboxRecord(result.getString(1), "Promotion", result.getString(2), result.getString(3), result.getInt(4), result.getTimestamp(5).toInstant(), result.getString(6), result.getString(7))) } } } }
    override fun markPublished(ids: List<String>, publishedAt: Instant) { if (ids.isEmpty()) return; transaction { connection -> connection.prepareStatement("UPDATE promotion_outbox_events SET published_at=? WHERE id=ANY(?)").use { statement -> statement.setTimestamp(1, publishedAt.timestamp()); statement.setArray(2, connection.createArrayOf("varchar", ids.toTypedArray())); statement.executeUpdate() } } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection -> connection.autoCommit = false; try { block(connection).also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } }
}

@Serializable data class CouponResponse(val id: String, val promotionId: String, val code: String, val status: String, val usageLimit: Long?, val usageCount: Long, val perUserLimit: Long?)
private fun Instant.timestamp() = Timestamp.from(this)
