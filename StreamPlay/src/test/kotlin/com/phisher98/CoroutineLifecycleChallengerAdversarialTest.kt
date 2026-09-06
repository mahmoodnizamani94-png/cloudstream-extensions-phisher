package com.phisher98

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Challenger Adversarial Stress Harness for Milestone 4 (R5)
 * Focus areas:
 * 1. Rapid TV back-navigation cancellation (< 250ms) with zero orphaned coroutines
 * 2. Canary permit restoration and circuit preservation under repetitive cancellation thrashing
 * 3. Supervisor scope isolation under catastrophic multi-exception chaos
 * 4. Semaphore permit bounding and recovery under sudden cancellation mid-flight
 * 5. Ultima and StremioAddon supervisor + semaphore cancellation parity
 */
class CoroutineLifecycleChallengerAdversarialTest {

    class MutableTimeProvider(var currentTime: Long = 5_000_000L) : TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        fun advanceTime(ms: Long) { currentTime += ms }
    }

    private lateinit var timeProvider: MutableTimeProvider
    private lateinit var circuitBreaker: CircuitBreaker

    @Before
    fun setUp() {
        timeProvider = MutableTimeProvider(5_000_000L)
        circuitBreaker = CircuitBreaker(timeProvider = timeProvider)
        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setTimeProviderForTesting(timeProvider)
        ProviderTelemetryManager.setCircuitBreakerForTesting(circuitBreaker)
        DeviceProfiler.resetForTesting()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setTimeProviderForTesting(SystemTimeProvider)
        DeviceProfiler.resetForTesting()
    }

    // ==================== 1. TV Back-Navigation Latency & Orphan Detection ====================

    @Test
    fun testTvBackNavigationAborts100ScrapersUnder250msWithoutOrphanedTasks() = runBlocking {
        val totalTasks = 100
        val concurrencyLimit = 12 // Low-end device concurrency limit
        val activeRunningTasks = AtomicInteger(0)
        val enteredCount = AtomicInteger(0)
        val cancelledCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)

        // 100 tasks: 3 fast resolvers (15ms), 97 deep scrapers (4000ms)
        val tasks: List<suspend () -> Unit> = (1..totalTasks).map { id ->
            suspend {
                enteredCount.incrementAndGet()
                activeRunningTasks.incrementAndGet()
                try {
                    if (id <= 3) {
                        delay(15)
                        completedCount.incrementAndGet()
                    } else {
                        delay(4000)
                        completedCount.incrementAndGet()
                    }
                } catch (e: CancellationException) {
                    cancelledCount.incrementAndGet()
                    throw e
                } finally {
                    activeRunningTasks.decrementAndGet()
                }
            }
        }

        val parentJob = launch(Dispatchers.IO) {
            StreamPlayConcurrency.runSupervisedLimitedAsync(
                concurrency = concurrencyLimit,
                taskTimeoutMs = 15_000L,
                isSatisfied = null,
                tasks = tasks
            )
        }

        // Allow fast tasks to execute and saturate initial semaphore permits
        var waited = 0L
        while (completedCount.get() < 3 && waited < 1000L) {
            delay(10)
            waited += 10L
        }

        assertTrue("Initial fast tasks must have completed", completedCount.get() >= 3)
        assertTrue("Some tasks are actively running", activeRunningTasks.get() > 0)

        // Adversarial Event: Android TV Back button clicked mid-scrape
        val cancelStartMs = System.currentTimeMillis()
        parentJob.cancelAndJoin()
        val cancelElapsedMs = System.currentTimeMillis() - cancelStartMs

        // Strict Requirement: TV back navigation aborts in < 250ms
        assertTrue(
            "Cancellation latency must be < 250ms, actual: ${cancelElapsedMs}ms",
            cancelElapsedMs < 250L
        )
        assertTrue("Parent job must be cancelled", parentJob.isCancelled)
        assertFalse("Parent job must not be active", parentJob.isActive)

        // Strict Invariant: Zero orphaned background tasks remaining in thread pool
        assertEquals(
            "Zero orphaned background tasks must be running after parent cancelAndJoin",
            0,
            activeRunningTasks.get()
        )
        assertTrue("Cancelled count must be positive", cancelledCount.get() > 0)
    }

    // ==================== 2. Canary Permit Restoration Under Cancellation Thrashing ====================

    @Test
    fun testCanaryPermitRestorationUnderRapidCancellationThrashing() = runBlocking {
        val provider = "ThrashCanaryProvider"

        // Trip provider to OPEN
        repeat(3) {
            circuitBreaker.recordFailure(provider, 1000L)
        }
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState(provider))

        // Advance into HALF_OPEN state
        timeProvider.advanceTime(15 * 60 * 1000L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(provider))

        // Thrash: 25 consecutive cycles of acquire canary -> start task -> cancel -> verify instant restore
        val cycles = 25
        for (i in 1..cycles) {
            val taskStarted = CompletableDeferred<Unit>()
            val permitRestored = AtomicBoolean(false)

            val job = launch(Dispatchers.IO) {
                var wasCancelled = false
                if (ProviderTelemetryManager.canExecute(provider)) {
                    taskStarted.complete(Unit)
                    try {
                        delay(5000) // Simulated long network scrape
                    } catch (e: CancellationException) {
                        wasCancelled = true
                        throw e
                    } finally {
                        if (wasCancelled) {
                            ProviderTelemetryManager.releaseCanaryPermit(provider)
                            permitRestored.set(true)
                        }
                    }
                }
            }

            taskStarted.await()
            assertTrue("Canary must be actively in flight during cycle $i", circuitBreaker.isCanaryActive(provider))

            // Cancel task mid-flight
            job.cancelAndJoin()

            assertTrue("Canary permit must be released in finally on cycle $i", permitRestored.get())
            assertFalse("Canary must no longer be active on cycle $i", circuitBreaker.isCanaryActive(provider))

            // Critical Invariant: Cancellation must NOT increment trip count or failure count
            assertEquals("Trip count must not increase from cancellation", 1, circuitBreaker.getTripCount(provider))
            assertEquals(
                "Circuit must remain HALF_OPEN (not tripped back to OPEN)",
                CircuitBreaker.CircuitState.HALF_OPEN,
                circuitBreaker.getState(provider)
            )

            // Critical Invariant: Next cycle can immediately acquire canary without waiting for 30s watchdog lease
            assertTrue("Subsequent caller must acquire canary immediately on cycle $i", ProviderTelemetryManager.canExecute(provider))
            // Release permit for next iteration setup
            ProviderTelemetryManager.releaseCanaryPermit(provider)
        }
    }

    // ==================== 3. Supervisor Scope Multi-Exception Chaos Isolation ====================

    @Test
    fun testSupervisorScopeMultiExceptionChaosIsolation() = runBlocking {
        val totalHealthy = 20
        val totalFailing = 40
        val healthyCompleted = AtomicInteger(0)
        val failingTriggered = AtomicInteger(0)

        val healthyTasks: List<suspend () -> Unit> = (1..totalHealthy).map {
            suspend {
                delay(30)
                healthyCompleted.incrementAndGet()
            }
        }

        val failingTasks: List<suspend () -> Unit> = (1..totalFailing).map { id ->
            suspend {
                delay(10)
                failingTriggered.incrementAndGet()
                when (id % 5) {
                    0 -> throw RuntimeException("Fatal socket reset simulation")
                    1 -> throw IllegalStateException("Malformed JSON payload simulation")
                    2 -> throw NoSuchElementException("Video stream token missing")
                    3 -> throw ArithmeticException("Divide by zero bitrate")
                    else -> throw NullPointerException("Null DOM pointer")
                }
            }
        }

        val allTasks = (healthyTasks + failingTasks).shuffled()

        // Execute under StreamPlayConcurrency supervisor
        StreamPlayConcurrency.runSupervisedLimitedAsync(
            concurrency = 8,
            taskTimeoutMs = 5000L,
            tasks = allTasks
        )

        // Critical Invariants:
        // 1. All failing tasks were triggered
        assertEquals("All failing tasks must have run", totalFailing, failingTriggered.get())
        // 2. All healthy tasks completed without being cancelled by failing siblings
        assertEquals("All healthy sibling tasks must complete despite chaotic failures", totalHealthy, healthyCompleted.get())
    }

    // ==================== 4. Semaphore Permit Bounding & Permit Leak Prevention ====================

    @Test
    fun testSemaphorePermitBoundingUnderSuddenCancellationMidFlight() = runBlocking {
        val concurrencyLimit = 6
        val semaphore = Semaphore(concurrencyLimit)
        val activeConcurrent = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val totalTasks = 50

        val tasks: List<suspend () -> Unit> = (1..totalTasks).map {
            suspend {
                semaphore.withPermit {
                    val current = activeConcurrent.incrementAndGet()
                    var max = maxObservedConcurrency.get()
                    while (current > max) {
                        if (maxObservedConcurrency.compareAndSet(max, current)) break
                        max = maxObservedConcurrency.get()
                    }
                    try {
                        delay(60)
                    } finally {
                        activeRunningTasksSafeDecrement(activeConcurrent)
                    }
                }
            }
        }

        val job = launch(Dispatchers.IO) {
            supervisorScope {
                tasks.map { task ->
                    launch { task() }
                }.joinAll()
            }
        }

        // Allow some tasks to acquire semaphore and begin execution
        delay(35)

        // Abrupt cancellation while tasks are running and dozens are waiting in semaphore queue
        job.cancelAndJoin()

        // Critical Invariant 1: Concurrency NEVER exceeded limit
        assertTrue(
            "Max observed concurrency (${maxObservedConcurrency.get()}) must not exceed limit ($concurrencyLimit)",
            maxObservedConcurrency.get() <= concurrencyLimit
        )

        // Critical Invariant 2: Semaphore permits cleanly returned (no permit leak!)
        assertEquals(
            "All permits must be restored after sudden cancellation",
            concurrencyLimit,
            semaphore.availablePermits
        )

        // Critical Invariant 3: Semaphore is fully operational for subsequent requests
        var subsequentSuccess = false
        semaphore.withPermit {
            subsequentSuccess = true
        }
        assertTrue("Semaphore must be reusable without deadlocks", subsequentSuccess)
    }

    // ==================== 5. Ultima & StremioAddon Cancellation Parity ====================

    @Test
    fun testUltimaMediaProvidersCancellationPatternParity() = runBlocking {
        val concurrency = 4
        val semaphore = Semaphore(concurrency)
        val providerId = "UltimaParityTestProvider"

        // Put provider in HALF_OPEN
        repeat(3) { circuitBreaker.recordFailure(providerId, 1000L) }
        timeProvider.advanceTime(15 * 60 * 1000L)
        assertTrue(ProviderTelemetryManager.canExecute(providerId))
        // Provider now has canary in flight
        assertTrue(circuitBreaker.isCanaryActive(providerId))

        val started = CompletableDeferred<Unit>()
        val wasCancelledFlag = AtomicBoolean(false)
        val canaryReleasedFlag = AtomicBoolean(false)

        // Mirror Ultima's supervisorScope + semaphore + canary release pattern
        val job = launch(Dispatchers.IO) {
            supervisorScope {
                launch {
                    semaphore.withPermit {
                        val start = System.currentTimeMillis()
                        var wasCancelled = false
                        try {
                            started.complete(Unit)
                            delay(3000)
                        } catch (e: CancellationException) {
                            wasCancelled = true
                            wasCancelledFlag.set(true)
                            throw e
                        } finally {
                            if (wasCancelled) {
                                ProviderTelemetryManager.releaseCanaryPermit(providerId)
                                canaryReleasedFlag.set(true)
                            }
                        }
                    }
                }
            }
        }

        started.await()
        job.cancelAndJoin()

        assertTrue("Was cancelled must be true", wasCancelledFlag.get())
        assertTrue("Canary must be released in finally block", canaryReleasedFlag.get())
        assertFalse("Canary must no longer be active", circuitBreaker.isCanaryActive(providerId))
        assertEquals("Available permits must be fully restored", concurrency, semaphore.availablePermits)
    }

    private fun activeRunningTasksSafeDecrement(counter: AtomicInteger) {
        counter.decrementAndGet()
    }
}
