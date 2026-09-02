package com.ecommerce.inventory

import com.ecommerce.platform.error.ApiException
import com.ecommerce.platform.error.ErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InventoryRepositoryValidationTest {
    @Test
    fun `create item rejects negative quantities`() {
        val repository = InventoryRepository(dataSource())
        val error = assertFailsWith<ApiException> { repository.createItem(input(onHand = -1), "actor-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
        assertFailsWith<ApiException> { repository.createItem(input(lowStockThreshold = -1), "actor-1", "corr-1") }
    }

    @Test
    fun `reserve rejects missing key empty items invalid ttl and quantity`() {
        val repository = InventoryRepository(dataSource())
        val item = ReservationItem("variant-1", "warehouse-1", 1)
        assertInvalidReservation(repository, ReservationInput("", "actor-1", null, null, listOf(item), 60))
        assertInvalidReservation(repository, ReservationInput("key-1", "actor-1", null, null, emptyList(), 60))
        assertInvalidReservation(repository, ReservationInput("key-1", "actor-1", null, null, listOf(item), 29))
        assertInvalidReservation(repository, ReservationInput("key-1", "actor-1", null, null, listOf(item), 86_401))
        assertFailsWith<IllegalArgumentException> { repository.reserve(ReservationInput("key-1", "actor-1", null, null, listOf(item.copy(quantity = 0)), 60), "corr-1") }
    }

    @Test
    fun `adjust rejects zero delta and blank reason`() {
        val repository = InventoryRepository(dataSource())
        val zero = assertFailsWith<ApiException> { repository.adjust("item-1", 0, AdjustmentType.CORRECTION, "reason", "actor-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, zero.errorCode)
        val blank = assertFailsWith<ApiException> { repository.adjust("item-1", 1, AdjustmentType.CORRECTION, " ", "actor-1", "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, blank.errorCode)
    }

    private fun assertInvalidReservation(repository: InventoryRepository, input: ReservationInput) {
        val error = assertFailsWith<ApiException> { repository.reserve(input, "corr-1") }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode)
    }

    private fun input(onHand: Long = 10, lowStockThreshold: Long = 2) = InventoryItemInput("warehouse-1", "WH1", "Main", "product-1", "variant-1", onHand, lowStockThreshold)

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
