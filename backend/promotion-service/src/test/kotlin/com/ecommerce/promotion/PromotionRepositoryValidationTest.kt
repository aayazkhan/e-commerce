package com.ecommerce.promotion

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromotionRepositoryValidationTest {
    private val repository = PromotionRepository(dataSource())

    @Test
    fun `promotion creation rejects invalid financial and quantity boundaries`() {
        assertInvalidPromotion(PromotionRequest("", PromotionType.PERCENTAGE, startAt = "2026-08-20T00:00:00Z"))
        assertInvalidPromotion(PromotionRequest("Sale", PromotionType.PERCENTAGE, startAt = "2026-08-20T00:00:00Z", minOrderMinor = -1))
        assertInvalidPromotion(PromotionRequest("Sale", PromotionType.PERCENTAGE, startAt = "2026-08-20T00:00:00Z", percentageBps = 0))
        assertInvalidPromotion(PromotionRequest("Sale", PromotionType.FIXED_AMOUNT, startAt = "2026-08-20T00:00:00Z", fixedAmountMinor = 0))
        assertInvalidPromotion(PromotionRequest("Sale", PromotionType.BUY_X_GET_Y, startAt = "2026-08-20T00:00:00Z", buyQuantity = 0, getQuantity = 1))
        assertInvalidPromotion(PromotionRequest("Sale", PromotionType.PERCENTAGE, startAt = "2026-08-20T00:00:00Z", usageLimit = -1, perUserLimit = 1))
    }

    @Test
    fun `promotion creation rejects malformed timestamps`() {
        val error = assertFailsWith<java.time.format.DateTimeParseException> {
            repository.create(PromotionRequest("Sale", PromotionType.PERCENTAGE, startAt = "not-a-timestamp"), "admin-1", "corr-1")
        }
        assertEquals("DateTimeParseException", error::class.simpleName)
    }

    @Test
    fun `promotion creation rejects every remaining invalid definition boundary`() {
        val base = PromotionRequest("Sale", PromotionType.PERCENTAGE, startAt = "2026-08-20T00:00:00Z", percentageBps = 100)
        listOf(
            base.copy(startAt = ""),
            base.copy(maxDiscountMinor = -1),
            base.copy(percentageBps = 10_001),
            base.copy(fixedAmountMinor = 0),
            base.copy(perUserLimit = -1),
            base.copy(buyQuantity = 0),
            base.copy(getQuantity = 0),
        ).forEach(::assertInvalidPromotion)
    }

    @Test
    fun `coupon creation and update reject invalid definitions`() {
        assertInvalidCoupon(PromotionRepository::createCoupon, CouponRequest("promotion-1", "a"))
        assertInvalidCoupon(PromotionRepository::createCoupon, CouponRequest("promotion-1", "x".repeat(65)))
        assertInvalidCoupon(PromotionRepository::createCoupon, CouponRequest("promotion-1", "SAVE10", usageLimit = -1))
        assertInvalidCoupon(PromotionRepository::createCoupon, CouponRequest("promotion-1", "SAVE10", perUserLimit = -1))
        val status = assertFailsWith<ApiException> { repository.updateCoupon("coupon-1", CouponUpdateRequest("UNKNOWN"), "admin-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, status.errorCode)
        val limit = assertFailsWith<ApiException> { repository.updateCoupon("coupon-1", CouponUpdateRequest("ACTIVE", perUserLimit = -1), "admin-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, limit.errorCode)
    }

    @Test
    fun `calculate and apply reject malformed cart input and idempotency`() {
        val invalidLine = PromotionCalculateRequest(lines = listOf(PromotionLine("p", "v", quantity = 0, unitPriceMinor = 100)))
        val calculate = assertFailsWith<ApiException> { repository.calculate("user-1", invalidLine) }
        assertEquals(ErrorCode.VALIDATION_ERROR, calculate.errorCode)
        val apply = assertFailsWith<ApiException> { repository.apply("user-1", PromotionCalculateRequest(lines = emptyList()), " ", null, "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, apply.errorCode)
        val longKey = assertFailsWith<ApiException> {
            repository.apply("user-1", PromotionCalculateRequest(lines = listOf(PromotionLine("p", "v", quantity = 1, unitPriceMinor = 1))), "k".repeat(129), null, "corr-1")
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, longKey.errorCode)
    }

    private fun assertInvalidPromotion(request: PromotionRequest) {
        val error = assertFailsWith<ApiException> { repository.create(request, "admin-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun assertInvalidCoupon(
        operation: (PromotionRepository, CouponRequest, String, String) -> CouponResponse,
        request: CouponRequest,
    ) {
        val error = assertFailsWith<ApiException> { operation(repository, request, "admin-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun dataSource(): DataSource {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, _ ->
            when (method.name) {
                "setAutoCommit", "rollback", "commit", "close" -> null
                else -> null
            }
        }) as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
            if (method.name == "getConnection") connection else null
        }) as DataSource
    }
}
