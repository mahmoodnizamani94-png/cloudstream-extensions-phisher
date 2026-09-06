package com.phisher98

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CircuitBreakerTest {

    class TestTimeProvider(var currentTime: Long = 1_000_000L) : TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        fun advanceTime(ms: Long) { currentTime += ms }
    }

    private lateinit var timeProvider: TestTimeProvider
    private lateinit var circuitBreaker: CircuitBreaker

    @Before
    fun setUp() {
        timeProvider = TestTimeProvider()
        circuitBreaker = CircuitBreaker(timeProvider = timeProvider)
    }

    @Test
    fun testNormalOperationInClosedState() {
        assertTrue("Provider in CLOSED state must allow execution", circuitBreaker.canExecute("test_prov"))
        circuitBreaker.recordSuccess("test_prov", 200L)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState("test_prov"))
        assertEquals(0, circuitBreaker.getConsecutiveFailures("test_prov"))
        assertEquals(0, circuitBreaker.getTripCount("test_prov"))
    }

    @Test
    fun testTripToOpenAfterThreeConsecutiveFailures() {
        circuitBreaker.recordFailure("test_prov", 5000L)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState("test_prov"))
        assertEquals(1, circuitBreaker.getConsecutiveFailures("test_prov"))

        circuitBreaker.recordFailure("test_prov", 5000L)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState("test_prov"))
        assertEquals(2, circuitBreaker.getConsecutiveFailures("test_prov"))

        circuitBreaker.recordFailure("test_prov", 5000L)
        // 3rd failure trips the breaker immediately
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState("test_prov"))
        assertEquals(1, circuitBreaker.getTripCount("test_prov"))
        assertFalse("Fast-fail check must return false in OPEN state", circuitBreaker.canExecute("test_prov"))
    }

    @Test
    fun testFastFailInOpenStateWithoutNetworkCalls() {
        // Trip breaker
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) }
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState("test_prov"))

        // Advance 14 minutes (1 minute before base 15m cooldown)
        timeProvider.advanceTime(14 * 60 * 1000L)
        assertFalse("Must remain OPEN at 14m", circuitBreaker.canExecute("test_prov"))
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState("test_prov"))
        assertTrue("Remaining cooldown must be ~1 min", circuitBreaker.getCooldownRemainingMs("test_prov") > 0)
    }

    @Test
    fun testTransitionToHalfOpenAfterBaseCooldown() {
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) }

        // Advance exactly 15 minutes
        timeProvider.advanceTime(15 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState("test_prov"))
    }

    @Test
    fun testSingleFlightCanaryBlocksThunderingHerd() = runBlocking {
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L) // Cooldown elapsed -> HALF_OPEN

        val concurrency = 50
        val permitCount = AtomicInteger(0)

        val jobs = (1..concurrency).map {
            async(Dispatchers.Default) {
                if (circuitBreaker.canExecute("test_prov")) {
                    permitCount.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        // CRITICAL INVARIANT: Exactly ONE request acquired the canary permit
        assertEquals("Exactly one caller must acquire canary permit across 50 threads", 1, permitCount.get())
        assertTrue("Canary must be reported as active", circuitBreaker.isCanaryActive("test_prov"))
    }

    @Test
    fun testCanarySuccessTransitionsToClosedAndResetsCooldown() {
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L)

        // Acquire canary
        assertTrue(circuitBreaker.canExecute("test_prov"))
        assertTrue(circuitBreaker.isCanaryActive("test_prov"))

        // Canary probe succeeds
        circuitBreaker.recordSuccess("test_prov", 400L)

        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState("test_prov"))
        assertEquals(0, circuitBreaker.getTripCount("test_prov"))
        assertEquals(0, circuitBreaker.getConsecutiveFailures("test_prov"))
        assertFalse(circuitBreaker.isCanaryActive("test_prov"))
        assertTrue("All subsequent callers can execute freely", circuitBreaker.canExecute("test_prov"))
    }

    @Test
    fun testCanaryFailureDoublesCooldownExponentially() {
        // Initial trip: 15m cooldown
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) }
        assertEquals(1, circuitBreaker.getTripCount("test_prov"))

        // Elapse 15m -> HALF_OPEN
        timeProvider.advanceTime(15 * 60 * 1000L)
        assertTrue(circuitBreaker.canExecute("test_prov")) // Canary acquired

        // Canary fails -> trips back to OPEN, tripCount = 2, cooldown = 30m
        circuitBreaker.recordFailure("test_prov", 6000L)
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState("test_prov"))
        assertEquals(2, circuitBreaker.getTripCount("test_prov"))

        // Advance 25 minutes (less than 30m) -> still OPEN
        timeProvider.advanceTime(25 * 60 * 1000L)
        assertFalse("Must remain OPEN at 25m into 30m cooldown", circuitBreaker.canExecute("test_prov"))

        // Advance 5 more minutes (total 30m elapsed) -> transitions to HALF_OPEN
        timeProvider.advanceTime(5 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState("test_prov"))

        // Acquire canary again
        assertTrue(circuitBreaker.canExecute("test_prov"))

        // Canary fails a 2nd time -> tripCount = 3, cooldown = 60m
        circuitBreaker.recordFailure("test_prov", 6000L)
        assertEquals(3, circuitBreaker.getTripCount("test_prov"))

        // Advance 55 minutes -> still OPEN
        timeProvider.advanceTime(55 * 60 * 1000L)
        assertFalse(circuitBreaker.canExecute("test_prov"))

        // Advance 5 more minutes (total 60m elapsed) -> HALF_OPEN
        timeProvider.advanceTime(5 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState("test_prov"))
    }

    @Test
    fun testClampedMaximumCooldownAtTwoHours() {
        // Trip 4 times consecutively: 15m -> 30m -> 60m -> 120m -> 120m
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) } // Trip 1 (15m)

        timeProvider.advanceTime(15 * 60 * 1000L)
        circuitBreaker.canExecute("test_prov")
        circuitBreaker.recordFailure("test_prov", 1000L) // Trip 2 (30m)

        timeProvider.advanceTime(30 * 60 * 1000L)
        circuitBreaker.canExecute("test_prov")
        circuitBreaker.recordFailure("test_prov", 1000L) // Trip 3 (60m)

        timeProvider.advanceTime(60 * 60 * 1000L)
        circuitBreaker.canExecute("test_prov")
        circuitBreaker.recordFailure("test_prov", 1000L) // Trip 4 (120m)
        assertEquals(4, circuitBreaker.getTripCount("test_prov"))

        timeProvider.advanceTime(120 * 60 * 1000L)
        circuitBreaker.canExecute("test_prov")
        circuitBreaker.recordFailure("test_prov", 1000L) // Trip 5 (clamped to 120m)
        assertEquals(5, circuitBreaker.getTripCount("test_prov"))

        // Verify remaining cooldown is at most 2 hours (7,200,000 ms)
        val remaining = circuitBreaker.getCooldownRemainingMs("test_prov")
        assertTrue("Cooldown must be clamped to 120m", remaining <= 120 * 60 * 1000L && remaining > 119 * 60 * 1000L)
    }

    @Test
    fun testCoroutineCancellationReleasesCanaryWithoutTripping() = runBlocking {
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L) // HALF_OPEN

        try {
            circuitBreaker.executeWithBreaker("test_prov") {
                throw CancellationException("Search satisfied early")
            }
        } catch (_: CancellationException) {
            // Expected
        }

        // Must NOT trip to OPEN, must remain in HALF_OPEN, permit must be cleared
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState("test_prov"))
        assertFalse("Canary must not be in-flight", circuitBreaker.isCanaryActive("test_prov"))
        assertEquals(1, circuitBreaker.getTripCount("test_prov")) // tripCount NOT incremented
        assertTrue("Subsequent canary probe can be attempted", circuitBreaker.canExecute("test_prov"))
    }

    @Test
    fun testWatchdogLeaseExpiryPreventsDeadlock() {
        repeat(3) { circuitBreaker.recordFailure("test_prov", 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L)

        // Acquire canary
        assertTrue(circuitBreaker.canExecute("test_prov"))
        assertFalse("Second caller blocked", circuitBreaker.canExecute("test_prov"))

        // Simulate orphaned lease: advance time past 30s watchdog threshold
        timeProvider.advanceTime(31 * 1000L)

        // New caller should be granted lease due to watchdog expiry
        assertTrue("Watchdog must allow new caller to acquire expired canary permit", circuitBreaker.canExecute("test_prov"))
    }

    @Test
    fun testConcurrentStressExecution() {
        val threads = 16
        val opsPerThread = 500
        val latch = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        val providerId = "prov_${i % 10}"
                        if (circuitBreaker.canExecute(providerId)) {
                            if (i % 3 == 0) {
                                circuitBreaker.recordFailure(providerId, 500L)
                            } else {
                                circuitBreaker.recordSuccess(providerId, 200L)
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Concurrent stress operations completed without deadlock", completed)
    }
}
