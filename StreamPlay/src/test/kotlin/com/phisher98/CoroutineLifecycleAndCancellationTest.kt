package com.phisher98

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

class CoroutineLifecycleAndCancellationTest {

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

    @Test
    fun testSimulatedTvBackNavigationRapidCancellation() = runBlocking {
        val startedCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val cancelledCount = AtomicInteger(0)

        // 30 simulated provider scrapers: 2 fast (20ms), 28 slow (2000ms)
        val tasks: List<suspend () -> Unit> = (1..30).map { id ->
            suspend {
                startedCount.incrementAndGet().let { }
                try {
                    if (id <= 2) {
                        delay(20)
                        completedCount.incrementAndGet().let { }
                    } else {
                        delay(2000)
                        completedCount.incrementAndGet().let { }
                    }
                } catch (e: CancellationException) {
                    cancelledCount.incrementAndGet().let { }
                    throw e
                }
            }
        }

        val parentJob = launch(Dispatchers.IO) {
            StreamPlayConcurrency.runSupervisedLimitedAsync(
                concurrency = 12,
                taskTimeoutMs = 10_000L,
                isSatisfied = null,
                tasks = tasks
            )
        }

        // Allow fast tasks to finish and slow tasks to enter delay
        var waitElapsed = 0L
        while (completedCount.get() < 2 && waitElapsed < 3000L) {
            delay(10)
            waitElapsed += 10L
        }

        // User presses "Back" button on Android TV remote: cancels parent coroutine scope
        val cancelStart = System.currentTimeMillis()
        parentJob.cancel()
        parentJob.join()
        val cancelDuration = System.currentTimeMillis() - cancelStart

        assertTrue("Cancellation completed promptly (< 250ms), took ${cancelDuration}ms", cancelDuration < 250L)
        assertTrue("Parent job is cancelled", parentJob.isCancelled)
        assertFalse("Parent job is no longer active", parentJob.isActive)
        assertTrue("Fast providers completed before back press", completedCount.get() >= 2)
        assertTrue("Slow providers received cancellation", cancelledCount.get() > 0)
    }

    @Test
    fun testCanaryPermitReleasedOnBackCancellation() = runBlocking {
        val providerId = "CanaryTestProvider"

        // Trip circuit breaker and advance time to enter HALF_OPEN state
        repeat(3) {
            ProviderTelemetryManager.recordExecution(providerId, success = false, durationMs = 2000L)
        }
        assertTrue("Provider must be OPEN", ProviderTelemetryManager.isCircuitBroken(providerId))

        timeProvider.advanceTime(15 * 60 * 1000L + 1000L)
        assertTrue("Provider must be in HALF_OPEN canary state", ProviderTelemetryManager.isRecovering(providerId))

        val canaryAcquired = AtomicBoolean(false)
        val canaryReleased = AtomicBoolean(false)
        val taskEntered = CompletableDeferred<Unit>()

        val canaryTask: suspend () -> Unit = {
            if (ProviderTelemetryManager.canExecute(providerId)) {
                canaryAcquired.set(true)
                taskEntered.complete(Unit)
                var wasCancelled = false
                try {
                    delay(3000) // simulated in-flight canary network call
                } catch (e: CancellationException) {
                    wasCancelled = true
                    throw e
                } finally {
                    if (wasCancelled) {
                        ProviderTelemetryManager.releaseCanaryPermit(providerId)
                        canaryReleased.set(true)
                    }
                }
            }
        }

        val job = launch(Dispatchers.IO) {
            StreamPlayConcurrency.runSupervisedLimitedAsync(
                concurrency = 4,
                tasks = listOf(canaryTask)
            )
        }

        taskEntered.await()
        assertTrue("Canary permit acquired", canaryAcquired.get())

        // User backs out: cancel in-flight canary
        job.cancel()
        job.join()

        assertTrue("Canary permit cleanly released in finally block", canaryReleased.get())
        // Verify another canary can now be acquired without deadlock
        assertTrue("Subsequent canary probe can execute", ProviderTelemetryManager.canExecute(providerId))
    }

    @Test
    fun testEarlySatisfactionAbortsLaggingProviders() = runBlocking {
        val linksAcquired = AtomicInteger(0)
        val cancelledLaggingCount = AtomicInteger(0)

        val tasks: List<suspend () -> Unit> = (1..20).map { id ->
            suspend {
                if (id <= 2) {
                    delay(30)
                    linksAcquired.addAndGet(2).let { } // 4 links total -> satisfies condition
                } else {
                    try {
                        delay(3000) // lagging deep scrapers
                    } catch (e: CancellationException) {
                        cancelledLaggingCount.incrementAndGet().let { }
                        throw e
                    }
                }
            }
        }

        val startTime = System.currentTimeMillis()
        StreamPlayConcurrency.runSupervisedLimitedAsync(
            concurrency = 8,
            taskTimeoutMs = 10_000L,
            isSatisfied = { linksAcquired.get() >= 3 },
            tasks = tasks
        )
        val duration = System.currentTimeMillis() - startTime

        assertTrue("Early satisfaction short-circuited in < 600ms, took ${duration}ms", duration < 600L)
        assertTrue("Links threshold satisfied", linksAcquired.get() >= 4)
        assertTrue("Lagging providers aborted", cancelledLaggingCount.get() > 0)
    }

    @Test
    fun testSupervisorHierarchyExceptionIsolation() = runBlocking {
        val successCount = AtomicInteger(0)

        val tasks: List<suspend () -> Unit> = listOf(
            suspend { delay(20); successCount.incrementAndGet().let { } },
            suspend { delay(10); throw IllegalStateException("Simulated network crash") },
            suspend { delay(30); successCount.incrementAndGet().let { } },
            suspend { delay(15); throw RuntimeException("Socket reset") },
            suspend { delay(40); successCount.incrementAndGet().let { } }
        )

        // Supervisor must isolate exceptions: siblings succeed and parent does not fail
        StreamPlayConcurrency.runSupervisedLimitedAsync(
            concurrency = 4,
            tasks = tasks
        )

        assertEquals("All healthy sibling tasks completed despite sibling exceptions", 3, successCount.get())
    }

    @Test
    fun testSemaphorePermitExhaustionAndDrainSafety() = runBlocking {
        val concurrencyLimit = 4
        val semaphore = Semaphore(concurrencyLimit)
        val activeConcurrent = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val tasks: List<suspend () -> Unit> = (1..16).map {
            suspend {
                semaphore.withPermit {
                    val current = activeConcurrent.incrementAndGet()
                    var max = maxObservedConcurrency.get()
                    while (current > max) {
                        if (maxObservedConcurrency.compareAndSet(max, current)) break
                        max = maxObservedConcurrency.get()
                    }
                    delay(25)
                    activeConcurrent.decrementAndGet().let { }
                }
            }
        }

        StreamPlayConcurrency.runSupervisedLimitedAsync(
            concurrency = concurrencyLimit,
            tasks = tasks
        )

        assertTrue("Concurrency strictly bounded by semaphore", maxObservedConcurrency.get() <= concurrencyLimit)
        assertEquals("All semaphore permits released after completion", concurrencyLimit, semaphore.availablePermits)
    }
}
