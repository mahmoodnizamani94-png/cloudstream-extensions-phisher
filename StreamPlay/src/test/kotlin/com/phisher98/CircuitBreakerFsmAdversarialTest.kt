package com.phisher98

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CircuitBreakerFsmAdversarialTest {

    class TestTimeProvider(var currentTime: Long = 1_000_000L) : TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        fun advanceTime(ms: Long) { currentTime += ms }
    }

    private lateinit var timeProvider: TestTimeProvider
    private lateinit var circuitBreaker: CircuitBreaker

    @Before
    fun setUp() {
        timeProvider = TestTimeProvider(1_000_000L)
        circuitBreaker = CircuitBreaker(timeProvider = timeProvider)
    }

    // ==================== 1. 3-State FSM State Machine Transitions ====================

    @Test
    fun testFullFsmLifecycleClosedToOpenToHalfOpenToClosed() {
        val provider = "FsmProv"

        // 1. Initially CLOSED
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState(provider))
        assertTrue(circuitBreaker.canExecute(provider))

        // 2. Three consecutive failures -> transitions to OPEN
        circuitBreaker.recordFailure(provider, 1000L)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState(provider))
        circuitBreaker.recordFailure(provider, 1000L)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState(provider))
        circuitBreaker.recordFailure(provider, 1000L)

        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState(provider))
        assertFalse("Fast-fail check in OPEN must reject immediately", circuitBreaker.canExecute(provider))
        assertEquals(1, circuitBreaker.getTripCount(provider))

        // 3. Advance clock by base cooldown (15 minutes) -> transitions to HALF_OPEN
        timeProvider.advanceTime(15 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(provider))

        // 4. Single-flight canary acquires permit
        assertTrue("First caller acquires canary permit in HALF_OPEN", circuitBreaker.canExecute(provider))
        assertTrue(circuitBreaker.isCanaryActive(provider))

        // 5. Canary probe succeeds -> transitions back to CLOSED
        circuitBreaker.recordSuccess(provider, 300L)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState(provider))
        assertEquals(0, circuitBreaker.getConsecutiveFailures(provider))
        assertEquals(0, circuitBreaker.getTripCount(provider))
        assertFalse(circuitBreaker.isCanaryActive(provider))
        assertTrue("Post-recovery calls execute freely", circuitBreaker.canExecute(provider))
    }

    // ==================== 2. Exponential Backoff Scaling & 2-Hour Maximum Cap ====================

    @Test
    fun testExponentialBackoffCooldownDoublingProgression() {
        val provider = "BackoffProv"

        // Trip 1: 3 failures -> trips to OPEN with base cooldown (15 min)
        repeat(3) { circuitBreaker.recordFailure(provider, 1000L) }
        assertEquals(1, circuitBreaker.getTripCount(provider))
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState(provider))

        // Elapse 15m -> HALF_OPEN
        timeProvider.advanceTime(15 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(provider))
        assertTrue(circuitBreaker.canExecute(provider)) // Canary permit acquired

        // Canary fails -> Trip 2 (30 min cooldown)
        circuitBreaker.recordFailure(provider, 5000L)
        assertEquals(2, circuitBreaker.getTripCount(provider))
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState(provider))

        // At 20 minutes (less than 30m) -> still OPEN
        timeProvider.advanceTime(20 * 60 * 1000L)
        assertFalse("Still OPEN at 20 min into 30 min cooldown", circuitBreaker.canExecute(provider))

        // Advance 10 more minutes (total 30m) -> HALF_OPEN
        timeProvider.advanceTime(10 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(provider))
        assertTrue(circuitBreaker.canExecute(provider))

        // Canary fails again -> Trip 3 (60 min cooldown)
        circuitBreaker.recordFailure(provider, 5000L)
        assertEquals(3, circuitBreaker.getTripCount(provider))

        // At 50 minutes (less than 60m) -> still OPEN
        timeProvider.advanceTime(50 * 60 * 1000L)
        assertFalse(circuitBreaker.canExecute(provider))

        // Advance 10 more minutes (total 60m) -> HALF_OPEN
        timeProvider.advanceTime(10 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(provider))
        assertTrue(circuitBreaker.canExecute(provider))

        // Canary fails again -> Trip 4 (120 min cooldown)
        circuitBreaker.recordFailure(provider, 5000L)
        assertEquals(4, circuitBreaker.getTripCount(provider))

        timeProvider.advanceTime(120 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(provider))
        assertTrue(circuitBreaker.canExecute(provider))

        // Canary fails a 5th time -> clamped to 120 min max cooldown
        circuitBreaker.recordFailure(provider, 5000L)
        assertEquals(5, circuitBreaker.getTripCount(provider))
        val remainingCooldown = circuitBreaker.getCooldownRemainingMs(provider)
        assertTrue("Cooldown must be clamped at 120 min", remainingCooldown <= 120 * 60 * 1000L && remainingCooldown > 119 * 60 * 1000L)
    }

    @Test
    fun testRecoveryResetsExponentialBackoffToBase15Min() {
        val provider = "RecoveryResetProv"

        // Repeatedly fail until tripCount = 3 (60m cooldown)
        repeat(3) { circuitBreaker.recordFailure(provider, 1000L) } // Trip 1 (15m)
        timeProvider.advanceTime(15 * 60 * 1000L)
        circuitBreaker.canExecute(provider)
        circuitBreaker.recordFailure(provider, 1000L) // Trip 2 (30m)
        timeProvider.advanceTime(30 * 60 * 1000L)
        circuitBreaker.canExecute(provider)
        circuitBreaker.recordFailure(provider, 1000L) // Trip 3 (60m)
        assertEquals(3, circuitBreaker.getTripCount(provider))

        // Elapse 60m -> acquire canary
        timeProvider.advanceTime(60 * 60 * 1000L)
        assertTrue(circuitBreaker.canExecute(provider))

        // Canary succeeds! Must reset tripCount and cooldown to base 15m
        circuitBreaker.recordSuccess(provider, 500L)
        assertEquals(0, circuitBreaker.getTripCount(provider))
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState(provider))

        // If provider fails again later, next trip count must be 1 (15m, NOT 120m!)
        repeat(3) { circuitBreaker.recordFailure(provider, 1000L) }
        assertEquals(1, circuitBreaker.getTripCount(provider))
        val cooldown = circuitBreaker.getCooldownRemainingMs(provider)
        assertTrue("Cooldown must reset to base 15 min", cooldown <= 15 * 60 * 1000L && cooldown > 14 * 60 * 1000L)
    }

    // ==================== 3. Single-Flight Canary Isolation & Thundering Herd Rejection ====================

    @Test
    fun testSingleFlightCanaryIsolationUnderThunderingHerd() = runBlocking {
        val provider = "HerdProv"
        repeat(3) { circuitBreaker.recordFailure(provider, 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L) // Now HALF_OPEN

        val concurrency = 100
        val acquiredCount = AtomicInteger(0)
        val rejectedCount = AtomicInteger(0)

        val jobs = (1..concurrency).map {
            async(Dispatchers.Default) {
                if (circuitBreaker.canExecute(provider)) {
                    acquiredCount.incrementAndGet()
                } else {
                    rejectedCount.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        // Critical Invariant: Across 100 concurrent requests, exactly 1 wins canary permit
        assertEquals("Exactly ONE request must acquire canary permit", 1, acquiredCount.get())
        assertEquals("99 requests must be immediately rejected (fast-failed)", 99, rejectedCount.get())
        assertTrue("Canary must be marked active", circuitBreaker.isCanaryActive(provider))
    }

    @Test
    fun testInFlightCanaryLockoutUntilCompletion() {
        val provider = "LockoutProv"
        repeat(3) { circuitBreaker.recordFailure(provider, 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L)

        // First caller acquires canary
        assertTrue(circuitBreaker.canExecute(provider))
        assertTrue(circuitBreaker.isCanaryActive(provider))

        // Subsequent callers while canary is active must be rejected
        assertFalse("Second caller blocked during canary execution", circuitBreaker.canExecute(provider))
        assertFalse("Third caller blocked during canary execution", circuitBreaker.canExecute(provider))

        // Complete canary execution
        circuitBreaker.recordSuccess(provider, 400L)
        assertFalse(circuitBreaker.isCanaryActive(provider))
        assertTrue("Subsequent callers allowed once CLOSED", circuitBreaker.canExecute(provider))
    }

    // ==================== 4. Watchdog Lease Timeout (Deadlock Prevention) ====================

    @Test
    fun testWatchdogLeaseExpiryPreventsPermanentDeadlock() {
        val provider = "WatchdogProv"
        repeat(3) { circuitBreaker.recordFailure(provider, 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L)

        // Caller 1 acquires canary
        assertTrue(circuitBreaker.canExecute(provider))
        assertFalse("Concurrent caller rejected while lease active", circuitBreaker.canExecute(provider))

        // Simulate orphaned coroutine: advance time past 30s watchdog threshold
        timeProvider.advanceTime(31 * 1000L)

        // New caller must be granted lease due to watchdog expiry
        assertTrue("Watchdog must allow new caller to acquire expired canary permit", circuitBreaker.canExecute(provider))
    }

    // ==================== 5. Coroutine Cancellation Safety ====================

    @Test
    fun testCoroutineCancellationReleasesCanaryWithoutCountingAsFailure() = runBlocking {
        val provider = "CancellationProv"
        repeat(3) { circuitBreaker.recordFailure(provider, 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L)

        // Execute canary via executeWithBreaker and throw CancellationException
        try {
            circuitBreaker.executeWithBreaker(provider) {
                throw CancellationException("Search satisfied early by faster provider")
            }
        } catch (_: CancellationException) {
            // Expected
        }

        // Must NOT trip to OPEN with doubled cooldown!
        // Must remain in HALF_OPEN with canary released, tripCount unchanged
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(provider))
        assertFalse("Canary permit must be released", circuitBreaker.isCanaryActive(provider))
        assertEquals(1, circuitBreaker.getTripCount(provider))

        // Subsequent canary probe can be attempted
        assertTrue("Subsequent canary probe must be permitted", circuitBreaker.canExecute(provider))
    }

    // ==================== 6. High-Concurrency Multithreaded Stress Testing ====================

    @Test
    fun testHighContentionMultithreadedCircuitBreakerStress() {
        val threadCount = 20
        val operationsPerThread = 250
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val providers = (1..8).map { "StressProv_$it" }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (op in 0 until operationsPerThread) {
                        val prov = providers[op % providers.size]
                        if (circuitBreaker.canExecute(prov)) {
                            if (op % 4 == 0) {
                                circuitBreaker.recordFailure(prov, 100L)
                            } else {
                                circuitBreaker.recordSuccess(prov, 50L)
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All threads must finish without deadlocks", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        for (prov in providers) {
            val snapshot = circuitBreaker.getSnapshot(prov)
            assertTrue("Snapshot must have non-negative requests", snapshot.totalRequests >= 0)
        }
    }
}
