package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CancellationSafetyTest {

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
    fun testRapidTvBackNavigationCancelsAllActiveTiers() = runBlocking {
        val tasks = (1..20).map { id ->
            PipelinedTask("provider_$id", LatencyTier.TIER_1) {
                delay(2000)
            }
        }

        val parentJob = launch(Dispatchers.IO) {
            SpeculativePipeliner.executePipelined(tasks)
        }

        delay(30)

        // User hits Back button on Android TV remote
        val cancelStart = System.currentTimeMillis()
        parentJob.cancel()
        parentJob.join()
        val cancelDuration = System.currentTimeMillis() - cancelStart

        assertTrue("Cancellation must complete promptly (was ${cancelDuration}ms)", cancelDuration < 500L)
        assertFalse("Parent job must no longer be active", parentJob.isActive)
        assertTrue("Parent job must be cancelled", parentJob.isCancelled)
    }

    @Test
    fun testZeroOrphanedCanaryLocksOnCancellation() = runBlocking {
        val providerId = "recovering_canary_provider"

        // Trip circuit breaker to OPEN
        repeat(3) {
            circuitBreaker.recordFailure(providerId, 5000L)
        }
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState(providerId))

        // Advance past cooldown to enter HALF_OPEN
        timeProvider.advanceTime(circuitBreaker.config.baseCooldownMs + 100L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(providerId))

        val task = PipelinedTask(providerId, LatencyTier.TIER_3) {
            delay(1000)
        }

        val job = launch(Dispatchers.IO) {
            SpeculativePipeliner.executePipelined(
                tasks = listOf(task),
                config = EarlySatisfactionConfig(tier3DelayMs = 0L)
            )
        }

        // Wait until task acquires canary permit
        var waited = 0L
        while (!circuitBreaker.isCanaryActive(providerId) && waited < 1000L) {
            delay(10)
            waited += 10L
        }
        assertTrue("Canary lock should be held while probe is in flight", circuitBreaker.isCanaryActive(providerId))

        // Cancel mid-probe (simulating early satisfaction or navigation abort)
        job.cancel()
        job.join()

        // Verify canary permit was cleanly released in finally block
        assertFalse("Canary lock must be released on cancellation", circuitBreaker.isCanaryActive(providerId))
        assertTrue("Subsequent canary probe must be able to acquire permit without 30s lockout", circuitBreaker.canExecute(providerId))
    }

    @Test
    fun testZeroFalsePenaltiesInTelemetryOnCancellation() = runBlocking {
        val providers = (1..10).map { "provider_cancel_$it" }
        val tasks = providers.map { id ->
            PipelinedTask(id, LatencyTier.TIER_1) {
                delay(2000)
            }
        }

        val job = launch(Dispatchers.IO) {
            SpeculativePipeliner.executePipelined(tasks)
        }

        delay(30)
        job.cancel()
        job.join()

        // Check all providers: cancellation should NEVER be recorded as failure in telemetry
        providers.forEach { id ->
            val stats = ProviderTelemetryManager.getStats(id)
            assertEquals("Cancelled provider must have 0 execution count", 0, stats.executionCount)
            assertEquals("Cancelled provider must have 0 failures", 0, stats.failureCount)
            assertEquals("Cancelled provider must have 0 consecutive failures", 0, stats.consecutiveFailures)
            assertFalse("Cancelled provider must not be circuit broken", stats.isCircuitBroken)
        }
    }

    @Test
    fun testMassCancellationSemaphorePermitDrain() = runBlocking {
        val concurrencyLimit = 4
        val cancelledCount = AtomicInteger(0)

        val tasks = (1..30).map { id ->
            PipelinedTask("drain_task_$id", LatencyTier.TIER_0) {
                try {
                    delay(1500)
                } catch (e: CancellationException) {
                    cancelledCount.incrementAndGet()
                    throw e
                }
            }
        }

        val job = launch(Dispatchers.IO) {
            SpeculativePipeliner.executePipelined(
                tasks = tasks,
                maxConcurrencyOverride = concurrencyLimit
            )
        }

        delay(40)
        job.cancel()
        job.join()

        assertTrue("Tasks should have been cancelled", cancelledCount.get() > 0)

        // Subsequent pipeline run must acquire all permits and complete without deadlock
        val nextCompleted = AtomicInteger(0)
        val nextTasks = (1..concurrencyLimit).map { id ->
            PipelinedTask("next_task_$id", LatencyTier.TIER_0) {
                delay(10)
                nextCompleted.incrementAndGet()
            }
        }

        val nextResult = SpeculativePipeliner.executePipelined(
            tasks = nextTasks,
            maxConcurrencyOverride = concurrencyLimit
        )

        assertEquals("All subsequent tasks must execute without permit starvation", concurrencyLimit, nextCompleted.get())
    }

    @Test
    fun testRapidSuccessiveNavigationChurn() = runBlocking {
        repeat(20) { iteration ->
            val tasks = (1..8).map { id ->
                PipelinedTask("churn_${iteration}_$id", LatencyTier.TIER_1) {
                    delay(500)
                }
            }

            val job = launch(Dispatchers.IO) {
                SpeculativePipeliner.executePipelined(tasks)
            }
            delay(10)
            job.cancel()
            job.join()
        }

        // Verify system remains fully operational after 20 rapid cancellations
        var executed = false
        val finalTask = listOf(
            PipelinedTask("final_verify", LatencyTier.TIER_0) {
                executed = true
            }
        )
        SpeculativePipeliner.executePipelined(finalTask)
        assertTrue("Pipeline must remain fully functional after rapid navigation churn", executed)
    }
}
