package com.ecommerce.promotion

import com.ecommerce.platform.error.ApiException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromotionCalculationTest {
    private val start = Instant.parse("2020-01-01T00:00:00Z")
    private val end = Instant.parse("2099-01-01T00:00:00Z")

    @Test
    fun `calculate returns exact percentage fixed and capped discounts`() {
        val percentage = calculate(promotion(type = PromotionType.PERCENTAGE, percentageBps = 1_500), lines = listOf(line(quantity = 2, unitPrice = 1_000)))
        assertEquals(300, percentage.discountMinor)
        assertEquals(300, percentage.eligibleLineDiscounts["variant-1"])

        val fixed = calculate(promotion(type = PromotionType.FIXED_AMOUNT, fixedAmountMinor = 500), lines = listOf(line(quantity = 2, unitPrice = 1_000)))
        assertEquals(500, fixed.discountMinor)

        val fixedWithoutAmount = calculate(promotion(type = PromotionType.FIXED_AMOUNT, fixedAmountMinor = null), lines = listOf(line()))
        assertEquals(0, fixedWithoutAmount.discountMinor)

        val capped = calculate(promotion(type = PromotionType.PERCENTAGE, percentageBps = 9_000, maxDiscountMinor = 250), lines = listOf(line(quantity = 2, unitPrice = 1_000)))
        assertEquals(250, capped.discountMinor)
    }

    @Test
    fun `calculate supports product category seller buy get and free shipping types`() {
        val product = calculate(promotion(type = PromotionType.PRODUCT_DISCOUNT, percentageBps = 1_000, productIds = listOf("product-1")), listOf(line()))
        assertEquals(100, product.discountMinor)
        val category = calculate(promotion(type = PromotionType.CATEGORY_DISCOUNT, percentageBps = 1_000, categoryIds = listOf("category-1")), listOf(line(categoryId = "category-1")))
        assertEquals(100, category.discountMinor)
        val seller = calculate(promotion(type = PromotionType.PERCENTAGE, percentageBps = 1_000, sellerIds = listOf("seller-1")), listOf(line(sellerId = "seller-1")))
        assertEquals(100, seller.discountMinor)

        val buyGetResponse = promotion(type = PromotionType.BUY_X_GET_Y, buyQuantity = 2, getQuantity = 1)
        val buyGet = PromotionRepository(dataSource(promotionRow(buyGetResponse, buyQuantity = 2, getQuantity = 1))).calculate("user-1", PromotionCalculateRequest(lines = listOf(line(quantity = 6, unitPrice = 100)), promotionId = buyGetResponse.id))
        assertEquals(200, buyGet.discountMinor)
        val freeShipping = calculate(promotion(type = PromotionType.FREE_SHIPPING), listOf(line()))
        assertEquals(0, freeShipping.discountMinor)
        assertTrue(freeShipping.freeShipping)
    }

    @Test
    fun `calculate reports lifecycle eligibility and cart rejection reasons`() {
        assertReason("CURRENCY_NOT_SUPPORTED", promotion(currency = "USD"), PromotionCalculateRequest(currency = "INR", lines = listOf(line())))
        assertReason("CUSTOMER_SEGMENT_NOT_ELIGIBLE", promotion(customerSegments = listOf("VIP")), PromotionCalculateRequest(lines = listOf(line()), customerSegment = "DEFAULT"))
        assertReason("PROMOTION_INACTIVE", promotion(status = PromotionStatus.PAUSED), PromotionCalculateRequest(lines = listOf(line())))
        assertReason("PROMOTION_INACTIVE", promotion(startAt = "2099-01-01T00:00:00Z"), PromotionCalculateRequest(lines = listOf(line())))
        assertReason("PROMOTION_INACTIVE", promotion(endAt = "2020-01-01T00:00:00Z"), PromotionCalculateRequest(lines = listOf(line())))
        assertReason("MINIMUM_ORDER_NOT_MET", promotion(minOrderMinor = 1_001), PromotionCalculateRequest(lines = listOf(line())))
        assertReason("NO_ELIGIBLE_ITEMS", promotion(productIds = listOf("other-product")), PromotionCalculateRequest(lines = listOf(line())))

        val notFound = PromotionRepository(dataSource(emptyMap())).calculate("missing", PromotionCalculateRequest(lines = listOf(line())))
        assertEquals("PROMOTION_NOT_FOUND", notFound.reason)
        assertEquals("PROMOTION_NOT_FOUND", PromotionRepository(dataSource(emptyMap())).calculate(
            "missing", PromotionCalculateRequest(lines = listOf(line()), promotionId = null, couponCode = "MISSING"),
        ).reason)
        assertEquals("PROMOTION_NOT_FOUND", PromotionRepository(dataSource(emptyMap())).calculate(
            "missing", PromotionCalculateRequest(lines = listOf(line()), promotionId = null, couponCode = " "),
        ).reason)
    }

    @Test
    fun `calculate selects a normalized coupon when requested`() {
        val response = PromotionRepository(dataSource(promotionRow(promotion(), coupon = true))).calculate(
            "user-1", PromotionCalculateRequest(lines = listOf(line()), couponCode = " save10 ", promotionId = null)
        )
        assertEquals("SAVE10", response.couponCode)
        assertEquals(100, response.discountMinor)
    }

    @Test
    fun `coupon context is retained on rejected promotion quotes`() {
        val currencyMismatch = PromotionRepository(
            dataSource(promotionRow(promotion(currency = "USD"), coupon = true)),
        ).calculate("user-1", PromotionCalculateRequest(currency = "INR", lines = listOf(line()), couponCode = "SAVE10"))
        assertEquals("SAVE10", currencyMismatch.couponCode)
        assertEquals("CURRENCY_NOT_SUPPORTED", currencyMismatch.reason)

        val segmentMismatch = PromotionRepository(
            dataSource(promotionRow(promotion(customerSegments = listOf("VIP")), coupon = true)),
        ).calculate("user-1", PromotionCalculateRequest(lines = listOf(line()), couponCode = "SAVE10"))
        assertEquals("SAVE10", segmentMismatch.couponCode)
        assertEquals("CUSTOMER_SEGMENT_NOT_ELIGIBLE", segmentMismatch.reason)

        val inactive = PromotionRepository(
            dataSource(promotionRow(promotion(status = PromotionStatus.PAUSED), coupon = true)),
        ).calculate("user-1", PromotionCalculateRequest(lines = listOf(line()), couponCode = "SAVE10"))
        assertEquals("SAVE10", inactive.couponCode)
        assertEquals("PROMOTION_INACTIVE", inactive.reason)
    }

    @Test
    fun `calculate covers nullable dates rates and empty buy-get configuration`() {
        val noEnd = calculate(promotion(endAt = null), listOf(line()))
        assertEquals(100, noEnd.discountMinor)

        val noRate = calculate(promotion(percentageBps = null), listOf(line()))
        assertEquals(0, noRate.discountMinor)

        val productNoRate = calculate(
            promotion(type = PromotionType.PRODUCT_DISCOUNT, percentageBps = null, productIds = listOf("product-1")),
            listOf(line()),
        )
        assertEquals(0, productNoRate.discountMinor)

        val categoryNoRate = calculate(
            promotion(type = PromotionType.CATEGORY_DISCOUNT, percentageBps = null, categoryIds = listOf("category-1")),
            listOf(line(categoryId = "category-1")),
        )
        assertEquals(0, categoryNoRate.discountMinor)

        val zeroGroup = PromotionRepository(dataSource(promotionRow(promotion(type = PromotionType.BUY_X_GET_Y), buyQuantity = 0, getQuantity = 0))).calculate(
            "user-1",
            PromotionCalculateRequest(lines = listOf(line(quantity = 3, unitPrice = 100)), promotionId = "promotion-1"),
        )
        assertEquals(0, zeroGroup.discountMinor)
    }

    @Test
    fun `calculate returns coupon identity on rejected quotes and handles positive buy-get remainder`() {
        val couponPromotion = promotion(currency = "INR", productIds = listOf("other-product"))
        val repository = PromotionRepository(dataSource(promotionRow(couponPromotion, coupon = true)))
        val rejected = repository.calculate(
            "user-1",
            PromotionCalculateRequest(
                lines = listOf(line()),
                currency = "USD",
                couponCode = " save10 ",
            ),
        )
        assertEquals("CURRENCY_NOT_SUPPORTED", rejected.reason)
        assertEquals("SAVE10", rejected.couponCode)

        val buyGetResponse = promotion(type = PromotionType.BUY_X_GET_Y)
        val buyGet = PromotionRepository(
            dataSource(promotionRow(buyGetResponse, buyQuantity = 1, getQuantity = 3)),
        ).calculate(
            "user-1",
            PromotionCalculateRequest(
                lines = listOf(line(quantity = 3, unitPrice = 100)),
                promotionId = buyGetResponse.id,
            ),
        )
        assertEquals(200, buyGet.discountMinor)
    }

    @Test
    fun `calculate rejects scoped lines when optional category or seller identity is absent`() {
        assertReason(
            "NO_ELIGIBLE_ITEMS",
            promotion(categoryIds = listOf("category-1")),
            PromotionCalculateRequest(lines = listOf(line(categoryId = null))),
        )
        assertReason(
            "NO_ELIGIBLE_ITEMS",
            promotion(sellerIds = listOf("seller-1")),
            PromotionCalculateRequest(lines = listOf(line(sellerId = null))),
        )
        assertReason(
            "NO_ELIGIBLE_ITEMS",
            promotion(categoryIds = listOf("category-1")),
            PromotionCalculateRequest(lines = listOf(line(categoryId = "other-category"))),
        )
        assertReason(
            "NO_ELIGIBLE_ITEMS",
            promotion(sellerIds = listOf("seller-1")),
            PromotionCalculateRequest(lines = listOf(line(sellerId = "other-seller"))),
        )
    }

    @Test
    fun `calculate covers valid customer segments empty carts and negative line values`() {
        val segmentedResponse = promotion(customerSegments = listOf("VIP"))
        val segmented = PromotionRepository(dataSource(promotionRow(segmentedResponse))).calculate(
            "user-1", PromotionCalculateRequest(lines = listOf(line()), promotionId = segmentedResponse.id, customerSegment = "VIP"),
        )
        assertEquals(100, segmented.discountMinor)
        assertReason(
            "CUSTOMER_SEGMENT_NOT_ELIGIBLE",
            segmentedResponse,
            PromotionCalculateRequest(lines = listOf(line())),
        )

        assertEquals("NO_ELIGIBLE_ITEMS", calculate(promotion(productIds = listOf("other")), listOf(line(categoryId = "other-category", sellerId = "other-seller"))).reason)
        assertEquals("NO_ELIGIBLE_ITEMS", calculate(promotion(productIds = listOf("other"), categoryIds = listOf("category-1"), sellerIds = listOf("seller-1")), listOf(line())).reason)
        assertFailsWith<ApiException> {
            PromotionRepository(dataSource(promotionRow(promotion()))).calculate("user-1", PromotionCalculateRequest(lines = emptyList(), promotionId = "promotion-1"))
        }
        assertFailsWith<ApiException> {
            PromotionRepository(dataSource(promotionRow(promotion()))).calculate("user-1", PromotionCalculateRequest(lines = listOf(line(unitPrice = -1)), promotionId = "promotion-1"))
        }
    }

    @Test
    fun `calculate caps discounts and handles nullable buy get quantities`() {
        val cappedBySubtotal = calculate(
            promotion(type = PromotionType.FIXED_AMOUNT, fixedAmountMinor = 5_000),
            listOf(line(quantity = 1, unitPrice = 1_000)),
        )
        assertEquals(1_000, cappedBySubtotal.discountMinor)

        val missingGet = PromotionRepository(dataSource(promotionRow(promotion(type = PromotionType.BUY_X_GET_Y), buyQuantity = 2, getQuantity = null))).calculate(
            "user-1", PromotionCalculateRequest(lines = listOf(line(quantity = 3, unitPrice = 100)), promotionId = "promotion-1"),
        )
        assertEquals(0, missingGet.discountMinor)

        val missingBuy = PromotionRepository(dataSource(promotionRow(promotion(type = PromotionType.BUY_X_GET_Y), buyQuantity = null, getQuantity = 1))).calculate(
            "user-1", PromotionCalculateRequest(lines = listOf(line(quantity = 3, unitPrice = 100)), promotionId = "promotion-1"),
        )
        assertEquals(300, missingBuy.discountMinor)
    }

    @Test
    fun `calculate aggregates multiple eligible lines and does not discount incomplete buy-get groups`() {
        val multipleLines = calculate(
            promotion(type = PromotionType.PERCENTAGE, percentageBps = 1_000),
            listOf(
                line(quantity = 1, unitPrice = 1_000),
                line(quantity = 2, unitPrice = 2_000).copy(variantId = "variant-2"),
            ),
        )
        assertEquals(500, multipleLines.discountMinor)
        assertEquals(mapOf("variant-1" to 100L, "variant-2" to 400L), multipleLines.eligibleLineDiscounts)

        val incompleteGroupResponse = promotion(type = PromotionType.BUY_X_GET_Y, buyQuantity = 2, getQuantity = 1)
        val incompleteGroup = PromotionRepository(
            dataSource(promotionRow(incompleteGroupResponse, buyQuantity = 2, getQuantity = 1)),
        ).calculate(
            "user-1",
            PromotionCalculateRequest(
                lines = listOf(line(quantity = 1, unitPrice = 100)),
                promotionId = incompleteGroupResponse.id,
            ),
        )
        assertEquals(0, incompleteGroup.discountMinor)
    }

    @Test
    fun `calculate treats promotion start as inclusive and end as exclusive`() {
        val current = Instant.parse("2026-08-23T12:00:00Z")
        val clock = Clock.fixed(current, ZoneOffset.UTC)

        val activeAtStart = promotion(
            startAt = current.toString(),
            endAt = current.plusSeconds(1).toString(),
        )
        val startQuote = PromotionRepository(dataSource(promotionRow(activeAtStart)), clock).calculate(
            "user-1",
            PromotionCalculateRequest(lines = listOf(line()), promotionId = activeAtStart.id),
        )
        assertEquals(100, startQuote.discountMinor)

        val inactiveAtEnd = promotion(
            startAt = current.minusSeconds(1).toString(),
            endAt = current.toString(),
        )
        val endQuote = PromotionRepository(dataSource(promotionRow(inactiveAtEnd)), clock).calculate(
            "user-1",
            PromotionCalculateRequest(lines = listOf(line()), promotionId = inactiveAtEnd.id),
        )
        assertEquals("PROMOTION_INACTIVE", endQuote.reason)
        assertEquals(0, endQuote.discountMinor)
    }

    private fun calculate(response: PromotionResponse, lines: List<PromotionLine>) =
        PromotionRepository(dataSource(promotionRow(response))).calculate("user-1", PromotionCalculateRequest(lines = lines, promotionId = response.id))

    private fun assertReason(reason: String, response: PromotionResponse, input: PromotionCalculateRequest) {
        val actual = PromotionRepository(dataSource(promotionRow(response))).calculate("user-1", input.copy(promotionId = response.id))
        assertEquals(reason, actual.reason)
        assertEquals(0, actual.discountMinor)
    }

    private fun line(quantity: Int = 1, unitPrice: Long = 1_000, categoryId: String? = null, sellerId: String? = null) =
        PromotionLine("product-1", "variant-1", categoryId, sellerId, quantity, unitPrice)

    private fun promotion(
        type: PromotionType = PromotionType.PERCENTAGE,
        status: PromotionStatus = PromotionStatus.ACTIVE,
        currency: String = "INR",
        minOrderMinor: Long = 0,
        maxDiscountMinor: Long? = null,
        percentageBps: Int? = 1_000,
        fixedAmountMinor: Long? = null,
        productIds: List<String> = emptyList(),
        categoryIds: List<String> = emptyList(),
        sellerIds: List<String> = emptyList(),
        startAt: String = this.start.toString(),
        endAt: String? = this.end.toString(),
        buyQuantity: Int? = null,
        getQuantity: Int? = null,
        customerSegments: List<String> = emptyList(),
    ) = PromotionResponse("promotion-1", "Sale", type, status, startAt, endAt, currency, minOrderMinor, maxDiscountMinor, percentageBps, fixedAmountMinor, productIds, categoryIds, sellerIds, null, 0, null, StackPolicy.NO_STACK, 1, 1, customerSegments)

    private fun promotionRow(response: PromotionResponse, coupon: Boolean = false, buyQuantity: Int? = null, getQuantity: Int? = null): Map<String, Any?> = mapOf(
        "id" to response.id, "name" to response.name, "type" to response.type.name, "status" to response.status.name,
        "start_at" to Timestamp.from(Instant.parse(response.startAt)), "end_at" to response.endAt?.let { Timestamp.from(Instant.parse(it)) }, "currency" to response.currency,
        "min_order_minor" to response.minOrderMinor, "max_discount_minor" to response.maxDiscountMinor, "percentage_bps" to response.percentageBps, "fixed_amount_minor" to response.fixedAmountMinor,
        "buy_quantity" to buyQuantity, "get_quantity" to getQuantity, "product_ids" to JsonStrings.encode(response.productIds), "category_ids" to JsonStrings.encode(response.categoryIds), "seller_ids" to JsonStrings.encode(response.sellerIds),
        "usage_limit" to response.usageLimit, "usage_count" to response.usageCount, "per_user_limit" to response.perUserLimit, "stack_policy" to response.stackPolicy.name, "priority" to response.priority, "version" to response.version, "customer_segments" to JsonStrings.encode(response.customerSegments),
        "coupon_id" to if (coupon) "coupon-1" else null, "coupon_code" to if (coupon) "SAVE10" else null, "coupon_usage_limit" to null, "coupon_usage_count" to 0L, "coupon_per_user_limit" to null,
    )

    private object JsonStrings {
        fun encode(values: List<String>) = if (values.isEmpty()) "[]" else values.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
    }

    private fun dataSource(row: Map<String, Any?>): DataSource {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
            when (method.name) {
                "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty(), row)
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }) as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
            if (method.name == "getConnection") connection else defaultValue(method.returnType)
        }) as DataSource
    }

    private fun statement(sql: String, row: Map<String, Any?>): PreparedStatement = Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, _ ->
        when (method.name) {
            "executeQuery" -> resultSet(if ((sql.contains("FROM promotions") || sql.contains("JOIN coupons")) && row.isNotEmpty()) listOf(row) else emptyList())
            "close" -> null
            else -> defaultValue(method.returnType)
        }
    }) as PreparedStatement

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        var wasNull = false
        fun value(key: Any): Any? = rows.getOrNull(index)?.get(key.toString()).also { wasNull = it == null }
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
            val key = args?.firstOrNull() ?: ""
            when (method.name) {
                "next" -> ++index < rows.size
                "getString" -> value(key)?.toString()
                "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                "getLong" -> (value(key) as? Number)?.toLong() ?: 0L
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
