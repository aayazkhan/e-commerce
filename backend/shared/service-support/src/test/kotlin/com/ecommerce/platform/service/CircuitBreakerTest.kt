package com.ecommerce.platform.service

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitBreakerTest {
    @Test
    fun opensAfterBoundedTransportFailuresAndAllowsProbeAfterCooldown() {
        var now = 0L
        val breaker = CircuitBreaker(failureThreshold = 2, openDuration = Duration.ofNanos(10)) { now }
        repeat(2) { assertFailsWith<IllegalStateException> { breaker.execute { error("down") } } }
        assertFailsWith<CircuitBreakerOpenException> { breaker.execute { "blocked" } }
        now = 10L
        assertTrue(breaker.execute { true })
    }

    @Test
    fun rejectsNonPositiveThresholdAndCooldown() {
        assertFailsWith<IllegalArgumentException> { CircuitBreaker(failureThreshold = 0) }
        assertFailsWith<IllegalArgumentException> { CircuitBreaker(openDuration = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { CircuitBreaker(openDuration = Duration.ofNanos(-1)) }
    }

    @Test
    fun failedHalfOpenProbeReopensTheCircuit() {
        var now = 0L
        val breaker = CircuitBreaker(failureThreshold = 1, openDuration = Duration.ofNanos(10)) { now }

        assertFailsWith<IllegalStateException> { breaker.execute { error("initial failure") } }
        now = 10L
        assertFailsWith<IllegalStateException> { breaker.execute { error("probe failure") } }
        assertFailsWith<CircuitBreakerOpenException> { breaker.execute { "blocked again" } }
    }

    @Test
    fun successfulOperationResetsFailuresBeforeThreshold() {
        var now = 0L
        val breaker = CircuitBreaker(failureThreshold = 2, openDuration = Duration.ofNanos(10)) { now }

        assertFailsWith<IllegalStateException> { breaker.execute { error("transient") } }
        assertEquals("ok", breaker.execute { "ok" })
        assertFailsWith<IllegalStateException> { breaker.execute { error("again") } }
        assertEquals("still closed after one failure", breaker.execute { "still closed after one failure" })
    }
}
