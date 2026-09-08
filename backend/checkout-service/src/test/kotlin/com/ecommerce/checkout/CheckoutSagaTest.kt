package com.ecommerce.checkout

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckoutSagaTest {
    @Test
    fun `completed checkout is idempotent and does not call downstream services`() = runBlocking {
        val store = FakeStore(completed())
        val dependencies = FakeDependencies()

        val result = execute(store, dependencies, completed())

        assertEquals(CheckoutStatus.COMPLETED, result.status)
        assertTrue(dependencies.calls.isEmpty())
        assertTrue(store.checkpoints.isEmpty())
    }

    @Test
    fun `successful saga with promotion commits every reversible step`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies()
        val request = request(couponCode = "SAVE10")

        val result = execute(store, dependencies, initial(), request)

        assertEquals(CheckoutStatus.COMPLETED, result.status)
        assertEquals(CheckoutStep.COMPLETE, result.currentStep)
        assertEquals("order-1", result.orderId)
        assertEquals("reservation-1", result.reservationId)
        assertEquals("redemption-1", result.promotionRedemptionId)
        assertEquals("payment-1", result.payment?.id)
        assertEquals(
            listOf(
                CheckoutStep.VALIDATE_CART,
                CheckoutStep.RESERVE_INVENTORY,
                CheckoutStep.CREATE_ORDER,
                CheckoutStep.CREATE_PAYMENT,
                CheckoutStep.CREATE_PAYMENT,
                CheckoutStep.COMMIT_INVENTORY,
                CheckoutStep.CREATE_SHIPMENT,
                CheckoutStep.COMPLETE,
            ),
            store.checkpoints.map { it.second },
        )
        assertEquals(listOf("PAYMENT_PENDING", "PAYMENT_PROCESSING", "PAID", "CONFIRMED"), dependencies.orderTransitions)
        assertEquals(listOf("reservation-1"), dependencies.committedReservations)
        assertEquals(listOf("redemption-1"), dependencies.committedPromotions)
        assertEquals(listOf("shipment-1"), dependencies.createdShipments)
    }

    @Test
    fun `blank coupon code skips promotion application`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies()

        val result = execute(store, dependencies, initial(), request(couponCode = " "))

        assertEquals(CheckoutStatus.COMPLETED, result.status)
        assertTrue(dependencies.calls.none { it == "applyPromotion" })
        assertTrue(dependencies.committedPromotions.isEmpty())
    }

    @Test
    fun `action required payment stops before confirmation and shipment`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(paymentStatus = "PENDING")

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.PAYMENT_ACTION_REQUIRED, result.status)
        assertEquals(CheckoutStep.COMMIT_INVENTORY, result.currentStep)
        assertEquals("payment-1", result.payment?.id)
        assertTrue(dependencies.orderTransitions.isEmpty())
        assertTrue(dependencies.committedReservations.isEmpty())
        assertTrue(dependencies.createdShipments.isEmpty())
    }

    @Test
    fun `invalid quote fails without attempting reservation`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(quote = CheckoutValidationResponse(false, null, listOf("stale cart")))

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.FAILED, result.status)
        assertEquals(CheckoutStep.COMPLETE, result.currentStep)
        assertEquals("Cart is no longer valid.", result.error)
        assertTrue(dependencies.calls == listOf("validate"))
        assertFalse(dependencies.calls.contains("reserve"))
    }

    @Test
    fun `valid quote without totals and invalid quote with totals both fail before reservation`() = runBlocking {
        val missingTotals = execute(FakeStore(initial()), FakeDependencies(quote = CheckoutValidationResponse(true, null, emptyList())), initial())
        assertEquals(CheckoutStatus.FAILED, missingTotals.status)

        val invalidWithTotals = execute(FakeStore(initial()), FakeDependencies(quote = CheckoutValidationResponse(false, totals(), listOf("stale cart"))), initial())
        assertEquals(CheckoutStatus.FAILED, invalidWithTotals.status)
    }

    @Test
    fun `captured and failed payment statuses stop or continue on their explicit branches`() = runBlocking {
        val capturedStore = FakeStore(initial())
        val capturedDependencies = FakeDependencies(paymentStatus = "CAPTURED")
        assertEquals(CheckoutStatus.COMPLETED, execute(capturedStore, capturedDependencies, initial()).status)
        assertEquals(listOf("shipment-1"), capturedDependencies.createdShipments)

        val failedStore = FakeStore(initial())
        val failedDependencies = FakeDependencies(paymentStatus = "FAILED")
        val failed = execute(failedStore, failedDependencies, initial())
        assertEquals(CheckoutStatus.PAYMENT_ACTION_REQUIRED, failed.status)
        assertTrue(failedDependencies.createdShipments.isEmpty())
    }

    @Test
    fun `retryable order failure releases reservation and remains recoverable`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(failure = FailureAt.ORDER)

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals(CheckoutStep.COMPLETE, result.currentStep)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
        assertEquals("order dependency unavailable", result.error)
    }

    @Test
    fun `shipment failure compensates promotion payment and reservation`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(failure = FailureAt.SHIPMENT)
        val totals = totals()

        val result = execute(store, dependencies, initial(), request(couponCode = "SAVE10"))

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
        assertEquals(listOf("redemption-1"), dependencies.releasedPromotions)
        assertEquals(listOf("REFUND_PENDING"), dependencies.orderTransitions.takeLast(1))
        assertEquals(listOf(RefundCall("payment-1", totals.totalMinor, totals.currency)), dependencies.refunds)
    }

    @Test
    fun `unexpected dependency exception releases reservation and is recoverable`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(failure = FailureAt.ORDER, genericFailure = true)

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals("Checkout could not be completed.", result.error)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
    }

    @Test
    fun `unexpected failure contains reservation compensation failure`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(
            failure = FailureAt.ORDER,
            genericFailure = true,
            compensationFailure = true,
        )

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals("Checkout could not be completed.", result.error)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
    }

    @Test
    fun `non retryable server error is still recoverable through status threshold`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(
            failure = FailureAt.ORDER,
            apiFailure = ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "gateway failed", 500, retryable = false),
        )

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals("gateway failed", result.error)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
    }

    @Test
    fun `non retryable client failure remains failed after compensation`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(
            failure = FailureAt.ORDER,
            apiFailure = ApiException(ErrorCode.CONFLICT, "order request is invalid", 409, retryable = false),
        )

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.FAILED, result.status)
        assertEquals("order request is invalid", result.error)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
    }

    @Test
    fun `generic failure before reservation is recoverable without compensation`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(genericValidationFailure = true)

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals("Checkout could not be completed.", result.error)
        assertTrue(dependencies.releasedReservations.isEmpty())
    }

    @Test
    fun `compensation provider failures are contained while checkout remains recoverable`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(
            failure = FailureAt.SHIPMENT,
            compensationFailure = true,
        )

        val result = execute(store, dependencies, initial(), request(couponCode = "SAVE10"))

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals("shipment dependency unavailable", result.error)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
        assertEquals(listOf("redemption-1"), dependencies.releasedPromotions)
        assertEquals(listOf("payment-1"), dependencies.refundAttempts)
    }

    @Test
    fun `api failure recovers persisted partial state with nullable totals`() = runBlocking {
        val persisted = initial().copy(
            orderId = "order-1",
            reservationId = "reservation-1",
            promotionRedemptionId = "redemption-1",
            payment = CheckoutPayment("payment-1", "AUTHORIZED"),
            totals = null,
        )
        val store = FakeStore(persisted)
        val dependencies = FakeDependencies(
            validationApiFailure = ApiException(ErrorCode.CONFLICT, "cart changed", 409),
        )

        val result = execute(store, dependencies, persisted)

        assertEquals(CheckoutStatus.FAILED, result.status)
        assertEquals(listOf(RefundCall("payment-1", 0, "INR")), dependencies.refunds)
        assertEquals(listOf("reservation-1"), dependencies.releasedReservations)
        assertEquals(listOf("redemption-1"), dependencies.releasedPromotions)
    }

    @Test
    fun `payment without an order does not attempt a refund during recovery`() = runBlocking {
        val persisted = initial().copy(payment = CheckoutPayment("payment-1", "AUTHORIZED"))
        val store = FakeStore(persisted)
        val dependencies = FakeDependencies(
            validationApiFailure = ApiException(ErrorCode.CONFLICT, "cart changed", 409),
        )

        val result = execute(store, dependencies, persisted)

        assertEquals(CheckoutStatus.FAILED, result.status)
        assertTrue(dependencies.refunds.isEmpty())
    }

    @Test
    fun `refund transition failure is contained without preventing checkout failure persistence`() = runBlocking {
        val store = FakeStore(initial())
        val dependencies = FakeDependencies(
            failure = FailureAt.SHIPMENT,
            compensationTransitionFailure = true,
        )

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals("shipment dependency unavailable", result.error)
        assertTrue(dependencies.refundAttempts.isEmpty())
    }

    @Test
    fun `missing persisted snapshot skips compensation and still records failure`() = runBlocking {
        val store = FakeStore(initial()).also { it.returnNullOnFirstGet = true }
        val dependencies = FakeDependencies(
            validationApiFailure = ApiException(ErrorCode.CONFLICT, "cart changed", 409),
        )

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.FAILED, result.status)
        assertTrue(dependencies.releasedReservations.isEmpty())
        assertEquals("cart changed", result.error)
    }

    @Test
    fun `unexpected failure with missing persisted snapshot records recoverable failure`() = runBlocking {
        val store = FakeStore(initial()).also { it.returnNullOnFirstGet = true }
        val dependencies = FakeDependencies(genericValidationFailure = true)

        val result = execute(store, dependencies, initial())

        assertEquals(CheckoutStatus.RECOVERABLE, result.status)
        assertEquals("Checkout could not be completed.", result.error)
        assertTrue(dependencies.releasedReservations.isEmpty())
    }

    private suspend fun execute(
        store: FakeStore,
        dependencies: FakeDependencies,
        response: CheckoutResponse,
        request: CheckoutRequest = request(),
    ) = runCheckoutSaga(store, dependencies, response, request, "user-1", "bearer", "correlation-1", "internal")

    private fun request(couponCode: String? = null) = CheckoutRequest(
        cartId = "cart-1",
        shippingAddressId = "address-1",
        paymentMethodToken = "payment-token",
        shippingMethod = CheckoutShippingMethod.EXPRESS,
        currency = "INR",
        couponCode = couponCode,
    )

    private fun totals() = CheckoutTotals(1_000, 0, 100, 50, 180, 1_130, "INR")

    private fun initial() = CheckoutResponse(
        checkoutId = "checkout-1",
        status = CheckoutStatus.CREATED,
        currentStep = CheckoutStep.VALIDATE_CART,
        createdAt = "2026-08-21T00:00:00Z",
        updatedAt = "2026-08-21T00:00:00Z",
    )

    private fun completed() = initial().copy(status = CheckoutStatus.COMPLETED, currentStep = CheckoutStep.COMPLETE)

    private enum class FailureAt { ORDER, SHIPMENT }

    private data class Checkpoint(val first: CheckoutStatus, val second: CheckoutStep)
    private data class RefundCall(val paymentId: String, val amount: Long, val currency: String)

    private class FakeStore(initial: CheckoutResponse) : CheckoutStore {
        var current = initial
        var returnNullOnFirstGet = false
        val checkpoints = mutableListOf<Checkpoint>()

        override fun start(userId: String, request: CheckoutRequest, key: String) = current
        override fun get(id: String): CheckoutResponse? {
            if (returnNullOnFirstGet) {
                returnNullOnFirstGet = false
                return null
            }
            return current.takeIf { it.checkoutId == id }
        }
        override fun getOwned(userId: String, id: String) = get(id)

        override fun checkpoint(
            id: String,
            status: CheckoutStatus,
            step: CheckoutStep,
            reservationId: String?,
            orderId: String?,
            promotionId: String?,
            paymentId: String?,
            paymentStatus: String?,
            paymentSecret: String?,
            shipmentId: String?,
            totals: CheckoutTotals?,
            error: String?,
        ) {
            checkpoints += Checkpoint(status, step)
            current = current.copy(
                status = status,
                currentStep = step,
                reservationId = reservationId ?: current.reservationId,
                orderId = orderId ?: current.orderId,
                promotionRedemptionId = promotionId ?: current.promotionRedemptionId,
                payment = paymentId?.let { CheckoutPayment(it, paymentStatus ?: "PROCESSING", paymentSecret) } ?: current.payment,
                totals = totals ?: current.totals,
                error = error,
                updatedAt = "2026-08-21T00:00:01Z",
            )
        }

        override fun fail(id: String, message: String, recoverable: Boolean) = checkpoint(
            id,
            if (recoverable) CheckoutStatus.RECOVERABLE else CheckoutStatus.FAILED,
            CheckoutStep.COMPLETE,
            error = message,
        )
    }

    private class FakeDependencies(
        val quote: CheckoutValidationResponse = CheckoutValidationResponse(true, CheckoutTotals(1_000, 0, 100, 50, 180, 1_130, "INR")),
        val paymentStatus: String = "AUTHORIZED",
        val failure: FailureAt? = null,
        val genericFailure: Boolean = false,
        val genericValidationFailure: Boolean = false,
        val validationApiFailure: ApiException? = null,
        val apiFailure: ApiException? = null,
        val compensationFailure: Boolean = false,
        val compensationTransitionFailure: Boolean = false,
    ) : CheckoutDependencies {
        val calls = mutableListOf<String>()
        val orderTransitions = mutableListOf<String>()
        val committedReservations = mutableListOf<String>()
        val releasedReservations = mutableListOf<String>()
        val committedPromotions = mutableListOf<String>()
        val releasedPromotions = mutableListOf<String>()
        val createdShipments = mutableListOf<String>()
        val refunds = mutableListOf<RefundCall>()
        val refundAttempts = mutableListOf<String>()

        override suspend fun validate(userId: String, bearer: String, request: CheckoutRequest): CheckoutValidationResponse {
            calls += "validate"
            if (genericValidationFailure) error("validation service failed")
            validationApiFailure?.let { throw it }
            return quote
        }

        override suspend fun details(userId: String, bearer: String, request: CheckoutRequest, totals: CheckoutTotals) = CheckoutDetails(
            listOf(OrderItemWire("product-1", "variant-1", "Product", "SKU-1", "seller-1", 2, 500, 0, 0, 1_000, "INR", "v1")),
            AddressSnapshotWire("address-1", "Customer", "+911234567890", "Line 1", null, "Pune", "MH", "411001", "IN"),
            AddressSnapshotWire("address-1", "Customer", "+911234567890", "Line 1", null, "Pune", "MH", "411001", "IN"),
            "cart-1",
        ).also { calls += "details" }

        override suspend fun reserve(userId: String, bearer: String, key: String, items: List<OrderItemWire>, token: String): ReservationWire {
            calls += "reserve"
            return reservation()
        }

        override suspend fun release(userId: String, token: String, id: String, key: String) { calls += "release"; releasedReservations += id; if (compensationFailure) error("release failed") }
        override suspend fun commit(userId: String, token: String, id: String, key: String) { calls += "commit"; committedReservations += id }

        override suspend fun createOrder(userId: String, bearer: String, token: String, checkoutId: String, reservationId: String, details: CheckoutDetails, totals: CheckoutTotals): OrderWire {
            calls += "order"
            if (failure == FailureAt.ORDER) fail("order dependency unavailable")
            return OrderWire("order-1", "INVENTORY_RESERVED", totals.totalMinor)
        }

        override suspend fun createPayment(userId: String, token: String, checkoutId: String, orderId: String, totals: CheckoutTotals, request: CheckoutRequest): PaymentWire {
            calls += "payment"
            return PaymentWire("payment-1", paymentStatus, "client-secret")
        }

        override suspend fun applyPromotion(userId: String, token: String, checkoutId: String, orderId: String, items: List<OrderItemWire>, request: CheckoutRequest, totals: CheckoutTotals): RedemptionWire {
            calls += "applyPromotion"
            return RedemptionWire("redemption-1", "promotion-1", request.couponCode, userId, orderId, 100, "INR", "RESERVED")
        }

        override suspend fun commitPromotion(userId: String, token: String, id: String) { calls += "commitPromotion"; committedPromotions += id }
        override suspend fun releasePromotion(userId: String, token: String, id: String) { calls += "releasePromotion"; releasedPromotions += id; if (compensationFailure) error("promotion release failed") }
        override suspend fun transitionOrder(orderId: String, status: String, token: String) { calls += "transition:$status"; orderTransitions += status; if (compensationTransitionFailure && status == "REFUND_PENDING") error("transition failed") }

        override suspend fun createShipment(userId: String, token: String, checkoutId: String, orderId: String, details: CheckoutDetails, totals: CheckoutTotals, request: CheckoutRequest): ShipmentWire {
            calls += "shipment"
            if (failure == FailureAt.SHIPMENT) fail("shipment dependency unavailable")
            createdShipments += "shipment-1"
            return ShipmentWire("shipment-1", "CREATED")
        }

        override suspend fun refund(paymentId: String, token: String, amount: Long, currency: String, checkoutId: String) {
            calls += "refund"
            refundAttempts += paymentId
            refunds += RefundCall(paymentId, amount, currency)
            if (compensationFailure) error("refund failed")
        }

        private fun fail(message: String): Nothing {
            apiFailure?.let { throw it }
            if (genericFailure) throw IllegalStateException(message)
            throw ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, message, 503, retryable = true)
        }
    }

    private companion object {
        fun reservation() = ReservationWire(
            "reservation-1", "checkout-1", "user-1", "cart-1", null, "RESERVED",
            "2026-08-21T00:15:00Z", "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z",
            listOf(ReservationItemWire("variant-1", "warehouse-1", 2)),
        )
    }
}
