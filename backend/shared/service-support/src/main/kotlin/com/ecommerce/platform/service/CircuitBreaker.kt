package com.ecommerce.platform.service

import java.time.Duration

class CircuitBreakerOpenException(message: String) : IllegalStateException(message)

/** Small, thread-safe transport circuit breaker. It does not retry requests. */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openDuration: Duration = Duration.ofSeconds(30),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val lock = Any()
    private var state = State.CLOSED
    private var failures = 0
    private var openedAtNanos = 0L

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(!openDuration.isNegative && !openDuration.isZero) { "openDuration must be positive" }
    }

    fun <T> execute(operation: () -> T): T {
        synchronized(lock) {
            if (state == State.OPEN) {
                if (nanoTime() - openedAtNanos >= openDuration.toNanos()) state = State.HALF_OPEN
                else throw CircuitBreakerOpenException("Downstream circuit is open")
            }
        }
        return try {
            operation().also { success() }
        } catch (error: Exception) {
            failure()
            throw error
        }
    }

    private fun success() = synchronized(lock) {
        state = State.CLOSED
        failures = 0
    }

    private fun failure() = synchronized(lock) {
        failures++
        if (failures >= failureThreshold) {
            state = State.OPEN
            openedAtNanos = nanoTime()
        }
    }
}
