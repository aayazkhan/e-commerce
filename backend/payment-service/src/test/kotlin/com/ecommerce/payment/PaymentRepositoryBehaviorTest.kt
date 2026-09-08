package com.ecommerce.payment

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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

class PaymentRepositoryBehaviorTest {
    private val request = PaymentCreateRequest("order-1", 1_000, "INR", PaymentProviderName.HTTP, "token")

    @Test
    fun `create finalizes provider payment and returns the same idempotent result`() {
        val database = FakePaymentDatabase()
        val repository = PaymentRepository(database.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider()))

        val created = repository.create("user-1", request, "key-1", "corr-1")
        assertEquals(PaymentStatus.CAPTURED, created.status)
        assertEquals(1_000, created.amountMinor)

        val repeated = repository.create("user-1", request, "key-1", "corr-2")
        assertEquals(created.id, repeated.id)
        assertEquals(created.status, repeated.status)

        val conflict = assertFailsWith<ApiException> {
            repository.create("user-1", request.copy(amountMinor = 2_000), "key-1", "corr-3")
        }
        assertEquals(ErrorCode.CONFLICT, conflict.errorCode)
        assertEquals(created, repository.getOwned("user-1", created.id))
        assertEquals(created, repository.getInternal(created.id))
        assertEquals(null, repository.getOwned("other-user", created.id))
    }

    @Test
    fun `an idempotent retry after an async provider webhook does not call the provider again`() {
        // Mirrors PayU's flow: create() only ever gets the provider to REQUIRES_ACTION
        // synchronously; a webhook (or our surl/furl callback) later moves the payment to
        // CAPTURED out of band. checkout-service's retry then calls create() again with the same
        // Idempotency-Key -- that replay must NOT call the provider a second time, since
        // finalize() would then try an invalid CAPTURED -> REQUIRES_ACTION transition and throw.
        val database = FakePaymentDatabase()
        val provider = FakeProvider(createStatus = PaymentStatus.REQUIRES_ACTION)
        val repository = PaymentRepository(database.dataSource(), mapOf(PaymentProviderName.HTTP to provider))

        val created = repository.create("user-1", request, "key-1", "corr-1")
        assertEquals(PaymentStatus.REQUIRES_ACTION, created.status)
        assertEquals(1, provider.createCalls)

        repository.webhook(
            PaymentProviderName.HTTP,
            PaymentWebhookRequest("event-1", "provider-1", PaymentStatus.CAPTURED, request.amountMinor, request.currency),
            "corr-2",
        )

        val retried = repository.create("user-1", request, "key-1", "corr-3")
        assertEquals(PaymentStatus.CAPTURED, retried.status)
        assertEquals(1, provider.createCalls)
    }

    @Test
    fun `create maps missing and duplicate providers while preserving database errors`() {
        val missingProvider = PaymentRepository(FakePaymentDatabase().dataSource(), emptyMap())
        val unavailable = assertFailsWith<ApiException> { missingProvider.create("user-1", request, "key-1", "corr") }
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable.errorCode)

        val duplicateDatabase = FakePaymentDatabase().also { it.failPaymentInsert = SQLException("duplicate", "23505") }
        val duplicate = assertFailsWith<ApiException> {
            PaymentRepository(duplicateDatabase.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider())).create("user-1", request, "key-1", "corr")
        }
        assertEquals(ErrorCode.CONFLICT, duplicate.errorCode)

        val providerFailure = FakePaymentDatabase()
        val failed = assertFailsWith<IllegalStateException> {
            PaymentRepository(providerFailure.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider(createFailure = true))).create("user-1", request, "key-1", "corr")
        }
        assertEquals("provider create failed", failed.message)

        val unavailableDatabase = FakePaymentDatabase().also { it.failPaymentInsert = SQLException("database unavailable", "08001") }
        val databaseFailure = assertFailsWith<SQLException> {
            PaymentRepository(unavailableDatabase.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider())).create("user-1", request, "key-1", "corr")
        }
        assertEquals("08001", databaseFailure.sqlState)
    }

    @Test
    fun `webhook validates amount and moves captured payment to refunded`() {
        val database = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "provider-1")
        val repository = PaymentRepository(database.dataSource(), emptyMap())
        val webhook = repository.webhook(
            PaymentProviderName.HTTP,
            PaymentWebhookRequest("event-1", "provider-1", PaymentStatus.REFUNDED, 1_000, "INR"),
            "corr-webhook",
        )
        assertEquals(PaymentStatus.REFUNDED, webhook.status)

        val duplicate = repository.webhook(
            PaymentProviderName.HTTP,
            PaymentWebhookRequest("event-1", "provider-1", PaymentStatus.REFUNDED, 1_000, "INR"),
            "corr-duplicate",
        )
        assertEquals(PaymentStatus.REFUNDED, duplicate.status)

        val mismatch = assertFailsWith<ApiException> {
            repository.webhook(PaymentProviderName.HTTP, PaymentWebhookRequest("event-2", "provider-1", PaymentStatus.CAPTURED, 999, "INR"), "corr")
        }
        assertEquals(ErrorCode.CONFLICT, mismatch.errorCode)

        val currencyMismatch = assertFailsWith<ApiException> {
            repository.webhook(PaymentProviderName.HTTP, PaymentWebhookRequest("event-2b", "provider-1", PaymentStatus.CAPTURED, 1_000, "USD"), "corr")
        }
        assertEquals(ErrorCode.CONFLICT, currencyMismatch.errorCode)

        val missing = assertFailsWith<ApiException> {
            repository.webhook(PaymentProviderName.HTTP, PaymentWebhookRequest("event-3", "missing", PaymentStatus.CAPTURED, 1_000, "INR"), "corr")
        }
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode)

        val invalidTransition = assertFailsWith<ApiException> {
            val terminal = FakePaymentDatabase(status = PaymentStatus.REFUNDED, providerPaymentId = "provider-1")
            PaymentRepository(terminal.dataSource(), emptyMap()).webhook(
                PaymentProviderName.HTTP,
                PaymentWebhookRequest("event-4", "provider-1", PaymentStatus.CANCELLED, 1_000, "INR"),
                "corr",
            )
        }
        assertEquals(ErrorCode.CONFLICT, invalidTransition.errorCode)

        val failedDatabase = FakePaymentDatabase(status = PaymentStatus.PROCESSING, providerPaymentId = "provider-1")
        val failedPayment = PaymentRepository(failedDatabase.dataSource(), emptyMap()).webhook(
            PaymentProviderName.HTTP,
            PaymentWebhookRequest("event-5", "provider-1", PaymentStatus.FAILED, 1_000, "INR"),
            "corr",
        )
        assertEquals(PaymentStatus.FAILED, failedPayment.status)

        val cancelledDatabase = FakePaymentDatabase(status = PaymentStatus.PROCESSING, providerPaymentId = "provider-1")
        val cancelledPayment = PaymentRepository(cancelledDatabase.dataSource(), emptyMap()).webhook(
            PaymentProviderName.HTTP,
            PaymentWebhookRequest("event-6", "provider-1", PaymentStatus.CANCELLED, 1_000, "INR"),
            "corr",
        )
        assertEquals(PaymentStatus.CANCELLED, cancelledPayment.status)

        val disappearingPayment = FakePaymentDatabase(status = PaymentStatus.PROCESSING, providerPaymentId = "provider-1").also {
            it.paymentMissingAfterFirstLookup = true
        }
        val lostDuringUpdate = assertFailsWith<ApiException> {
            PaymentRepository(disappearingPayment.dataSource(), emptyMap()).webhook(
                PaymentProviderName.HTTP,
                PaymentWebhookRequest("event-7", "provider-1", PaymentStatus.FAILED, 1_000, "INR"),
                "corr",
            )
        }
        assertEquals(ErrorCode.NOT_FOUND, lostDuringUpdate.errorCode)
    }

    @Test
    fun `refund validates ownership amount and provider result and supports idempotent prior result`() {
        val invalid = PaymentRepository(FakePaymentDatabase().dataSource(), emptyMap())
        assertError(ErrorCode.VALIDATION_ERROR) { invalid.refund("pay-1", null, RefundPaymentRequest(0, "INR", "r-1"), "corr") }
        assertError(ErrorCode.VALIDATION_ERROR) { invalid.refund("pay-1", null, RefundPaymentRequest(10, "inr", "r-1"), "corr") }

        val priorDb = FakePaymentDatabase().also { it.priorRefund = Triple(100L, "SUCCESS", "refund-1") }
        val prior = PaymentRepository(priorDb.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(100, "INR", "already"), "corr")
        assertEquals(PaymentStatus.REFUNDED, prior.status)
        assertEquals("refund-1", prior.providerRefundId)

        val pendingDb = FakePaymentDatabase().also { it.priorRefund = Triple(100L, "PENDING", "") }
        val pending = PaymentRepository(pendingDb.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(100, "INR", "pending"), "corr")
        assertEquals(PaymentStatus.REFUND_PENDING, pending.status)

        val database = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "provider-1")
        val repository = PaymentRepository(database.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider(refundStatus = PaymentStatus.REFUNDED)))
        val refunded = repository.refund("pay-1", "user-1", RefundPaymentRequest(1_000, "INR", "r-1"), "corr-refund")
        assertEquals(PaymentStatus.REFUNDED, refunded.status)
        assertEquals("refund-1", refunded.providerRefundId)

        val partialDatabase = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "provider-1")
        val partial = PaymentRepository(partialDatabase.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider(refundStatus = PaymentStatus.PARTIALLY_REFUNDED))).refund(
            "pay-1", "user-1", RefundPaymentRequest(500, "INR", "partial"), "corr-partial",
        )
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, partial.status)

        val failedProviderDatabase = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "provider-1")
        val failedProvider = PaymentRepository(failedProviderDatabase.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider(refundStatus = PaymentStatus.FAILED))).refund(
            "pay-1", "user-1", RefundPaymentRequest(500, "INR", "failed"), "corr-failed",
        )
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, failedProvider.status)

        assertError(ErrorCode.CONFLICT) {
            val overRefund = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "provider-1").also { it.refundAmount = 1_000 }
            PaymentRepository(overRefund.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(1, "INR", "over"), "corr")
        }
        assertError(ErrorCode.CONFLICT) {
            val currencyMismatch = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "provider-1")
            PaymentRepository(currencyMismatch.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(1, "USD", "currency"), "corr")
        }
        assertError(ErrorCode.DEPENDENCY_UNAVAILABLE) {
            val missingReference = FakePaymentDatabase(status = PaymentStatus.CAPTURED)
            PaymentRepository(missingReference.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(1, "INR", "missing-reference"), "corr")
        }
        assertError(ErrorCode.DEPENDENCY_UNAVAILABLE) {
            val blankReference = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "")
            PaymentRepository(blankReference.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(1, "INR", "blank-reference"), "corr")
        }
        assertError(ErrorCode.DEPENDENCY_UNAVAILABLE) {
            val missingProvider = FakePaymentDatabase(status = PaymentStatus.CAPTURED, providerPaymentId = "provider-1")
            PaymentRepository(missingProvider.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(1, "INR", "missing-provider"), "corr")
        }

        val notRefundable = FakePaymentDatabase(status = PaymentStatus.FAILED, providerPaymentId = "provider-1")
        assertError(ErrorCode.CONFLICT) {
            PaymentRepository(notRefundable.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(1, "INR", "r-1"), "corr")
        }
        assertError(ErrorCode.NOT_FOUND) {
            val missingPayment = FakePaymentDatabase().also { it.paymentMissing = true }
            PaymentRepository(missingPayment.dataSource(), emptyMap()).refund("pay-1", null, RefundPaymentRequest(1, "INR", "missing-payment"), "corr")
        }
    }

    @Test
    fun `reconciliation applies provider status and outbox boundaries are readable`() {
        val database = FakePaymentDatabase(status = PaymentStatus.PROCESSING, providerPaymentId = "provider-1")
        val repository = PaymentRepository(database.dataSource(), mapOf(PaymentProviderName.HTTP to FakeProvider(queryStatus = PaymentStatus.AUTHORIZED)))

        repository.reconcile(10, "corr-reconcile")
        assertEquals(PaymentStatus.AUTHORIZED, database.status)

        val events = repository.unpublished(10)
        assertEquals(1, events.size)
        assertEquals("Payment", events.single().aggregateType)
        repository.markPublished(events.map { it.id }, Instant.parse("2026-08-20T00:00:00Z"))
        repository.markPublished(emptyList(), Instant.now())
        assertNotNull(database.lastPublishedIds)
    }

    private fun assertError(code: ErrorCode, block: () -> Unit) {
        val error = assertFailsWith<ApiException> { block() }
        assertEquals(code, error.errorCode)
    }

    private class FakeProvider(
        private val createFailure: Boolean = false,
        private val refundStatus: PaymentStatus = PaymentStatus.REFUNDED,
        private val queryStatus: PaymentStatus = PaymentStatus.AUTHORIZED,
        private val createStatus: PaymentStatus = PaymentStatus.CAPTURED,
    ) : PaymentProvider {
        override val name = PaymentProviderName.HTTP
        var createCalls = 0
            private set
        override fun create(request: ProviderCreateRequest): ProviderPayment {
            createCalls += 1
            if (createFailure) error("provider create failed")
            return ProviderPayment("provider-1", createStatus, "client-secret")
        }
        override fun query(providerPaymentId: String) = ProviderPayment(providerPaymentId, queryStatus)
        override fun refund(providerPaymentId: String, amountMinor: Long, currency: String, idempotencyKey: String) = ProviderRefund("refund-1", refundStatus)
        override fun verifyWebhook(body: String, signature: String?) = true
    }

    private class FakePaymentDatabase(
        var status: PaymentStatus = PaymentStatus.PROCESSING,
        var providerPaymentId: String? = null,
    ) {
        var paymentId = "pay-1"
        var requestHash: String? = null
        var failPaymentInsert: SQLException? = null
        var paymentMissing = false
        var paymentMissingAfterFirstLookup = false
        private var paymentLookupCount = 0
        var priorRefund: Triple<Long, String, String>? = null
        var refundAmount = 0L
        var refundSuccess = false
        var lastPublishedIds: List<String>? = null

        fun dataSource(): DataSource {
            val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args?.firstOrNull()?.toString().orEmpty())
                    "setAutoCommit", "commit", "rollback", "close" -> null
                    "createArrayOf" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as Connection
            return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java), InvocationHandler { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            }) as DataSource
        }

        private fun statement(sql: String): PreparedStatement {
            val parameters = mutableMapOf<Int, Any?>()
            return Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), InvocationHandler { _, method, args ->
                when (method.name) {
                    "setString", "setInt", "setLong", "setTimestamp", "setArray" -> parameters[args!![0] as Int] = args[1]
                    "executeQuery" -> resultSet(query(sql, parameters))
                    "executeUpdate" -> update(sql, parameters)
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }) as PreparedStatement
        }

        private fun update(sql: String, p: Map<Int, Any?>): Int {
            when {
                sql.startsWith("INSERT INTO payment_intents") -> {
                    failPaymentInsert?.let { throw it }
                    paymentId = p[1].toString(); requestHash = p[9].toString(); status = PaymentStatus.valueOf(p[5].toString())
                }
                sql.startsWith("UPDATE payment_intents SET status") -> {
                    status = PaymentStatus.valueOf(p[1].toString())
                    if (p[2] != null) providerPaymentId = p[2].toString()
                }
                sql.startsWith("INSERT INTO payment_transactions") && p[3] == "REFUND" -> refundAmount = p[5].toString().toLong()
                sql.startsWith("UPDATE payment_transactions SET provider") -> { refundSuccess = p[2] == "SUCCESS"; }
                sql.startsWith("UPDATE payment_outbox_events") -> lastPublishedIds = (p[2] as? java.sql.Array)?.let { emptyList() } ?: listOf("event-1")
            }
            return 1
        }

        private fun query(sql: String, p: Map<Int, Any?>): List<Map<Any, Any?>> = when {
            sql.startsWith("SELECT request_hash,id") -> if (requestHash != null) listOf(mapOf<Any, Any?>(1 to requestHash, 2 to paymentId)) else emptyList()
            sql.startsWith("SELECT * FROM payment_intents") -> {
                val idMatches = if (sql.contains("provider_payment_id")) p[1] == providerPaymentId else p[1] == paymentId
                val userMatches = !sql.contains("user_id=?") || p[2] == "user-1"
                paymentLookupCount += 1
                if (!paymentMissing && !(paymentMissingAfterFirstLookup && paymentLookupCount > 1) && idMatches && userMatches) listOf(paymentRow()) else emptyList()
            }
            sql.startsWith("SELECT amount_minor,status,provider_transaction_id") -> priorRefund?.let { listOf(mapOf<Any, Any?>(1 to it.first, 2 to it.second, 3 to it.third, 4 to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")))) } ?: emptyList()
            sql.startsWith("SELECT COALESCE(SUM(amount_minor),0)") && sql.contains("status IN") -> listOf(mapOf<Any, Any?>(1 to refundAmount))
            sql.startsWith("SELECT COALESCE(SUM(amount_minor),0)") -> listOf(mapOf<Any, Any?>(1 to if (refundSuccess) refundAmount else 0L))
            sql.startsWith("SELECT id,provider,provider_payment_id") -> listOf(mapOf<Any, Any?>(1 to paymentId, 2 to "HTTP", 3 to (providerPaymentId ?: "provider-1")))
            sql.startsWith("SELECT id,aggregate_id,event_type") -> listOf(mapOf<Any, Any?>(1 to "event-1", 2 to paymentId, 3 to "PaymentCaptured", 4 to 1, 5 to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")), 6 to "corr", 7 to "{}"))
            else -> emptyList()
        }

        private fun paymentRow() = mapOf<Any, Any?>(
            "id" to paymentId, "order_id" to "order-1", "user_id" to "user-1", "provider" to "HTTP", "provider_payment_id" to providerPaymentId,
            "status" to status.name, "amount_minor" to 1_000L, "currency" to "INR", "attempt" to 1, "client_secret" to "client-secret",
            "created_at" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")), "updated_at" to Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        )

        private fun resultSet(rows: List<Map<Any, Any?>>): ResultSet {
            var index = -1
            fun value(key: Any): Any? = rows.getOrNull(index)?.get(key)
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java), InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() ?: ""
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> value(key)?.toString()
                    "getLong" -> (value(key) as? Number)?.toLong() ?: 0L
                    "getInt" -> (value(key) as? Number)?.toInt() ?: 0
                    "getTimestamp" -> value(key) as? Timestamp
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
}
