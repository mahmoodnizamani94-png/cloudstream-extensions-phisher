package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SpeculativePipelinerTest {

    private fun createLink(name: String, quality: Int = Qualities.P720.value): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = "TestSource",
            name = name,
            url = "https://example.com/video.mp4",
            referer = "https://example.com",
            quality = quality,
            type = ExtractorLinkType.VIDEO,
            headers = emptyMap()
        )
    }

    @Before
    fun setUp() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    @Test
    fun testTierExecutionOrder() = runBlocking {
        val tier0StartedAt = AtomicInteger(-1)
        val tier1StartedAt = AtomicInteger(-1)
        val tier2StartedAt = AtomicInteger(-1)

        val startTime = System.currentTimeMillis()

        val tasks = listOf(
            PipelinedTask(
                providerId = "p_tier0",
                initialTier = LatencyTier.TIER_0,
                isVideo = true
            ) {
                tier0StartedAt.set((System.currentTimeMillis() - startTime).toInt())
                delay(30)
            },
            PipelinedTask(
                providerId = "p_tier1",
                initialTier = LatencyTier.TIER_1,
                isVideo = true
            ) {
                tier1StartedAt.set((System.currentTimeMillis() - startTime).toInt())
                delay(30)
            },
            PipelinedTask(
                providerId = "p_tier2",
                initialTier = LatencyTier.TIER_2,
                isVideo = true
            ) {
                tier2StartedAt.set((System.currentTimeMillis() - startTime).toInt())
                delay(30)
            }
        )

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 5,
            tier1DelayMs = 40L,
            tier2DelayMs = 100L
        )

        SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config
        )

        val t0 = tier0StartedAt.get()
        val t1 = tier1StartedAt.get()
        val t2 = tier2StartedAt.get()

        assertTrue("Tier 0 should start immediately (was $t0 ms)", t0 in 0..40)
        assertTrue("Tier 1 should start after tier1DelayMs (was $t1 ms)", t1 >= 35)
        assertTrue("Tier 2 should start after tier2DelayMs (was $t2 ms)", t2 >= 90)
        assertTrue("Execution order should be Tier 0 -> Tier 1 -> Tier 2 ($t0 <= $t1 <= $t2)", t0 <= t1 && t1 <= t2)
    }

    @Test
    fun testStaggeringDelays() = runBlocking {
        val tier1Started = AtomicBoolean(false)
        val tier2Started = AtomicBoolean(false)
        val start = System.currentTimeMillis()
        var tier1DelayObserved = 0L
        var tier2DelayObserved = 0L

        val tasks = listOf(
            PipelinedTask("fast_tier0", LatencyTier.TIER_0) {
                delay(20)
            },
            PipelinedTask("med_tier1", LatencyTier.TIER_1) {
                tier1DelayObserved = System.currentTimeMillis() - start
                tier1Started.set(true)
                delay(20)
            },
            PipelinedTask("deep_tier2", LatencyTier.TIER_2) {
                tier2DelayObserved = System.currentTimeMillis() - start
                tier2Started.set(true)
                delay(20)
            }
        )

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 10,
            tier1DelayMs = 50L,
            tier2DelayMs = 120L
        )

        SpeculativePipeliner.executePipelined(tasks = tasks, config = config)

        assertTrue("Tier 1 should have executed", tier1Started.get())
        assertTrue("Tier 2 should have executed", tier2Started.get())
        assertTrue("Tier 1 delay >= 45ms (was ${tier1DelayObserved}ms)", tier1DelayObserved >= 45L)
        assertTrue("Tier 2 delay >= 110ms (was ${tier2DelayObserved}ms)", tier2DelayObserved >= 110L)
    }

    @Test
    fun testSkippingTier2And3OnEarlySatisfaction() = runBlocking {
        val tier1Executed = AtomicBoolean(false)
        val tier2Executed = AtomicBoolean(false)
        val tier3Executed = AtomicBoolean(false)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P1080.value,
            tier1DelayMs = 60L,
            tier2DelayMs = 200L,
            tier3DelayMs = 500L
        )
        val controller = EarlySatisfactionController(config)

        val tasks = listOf(
            PipelinedTask("tier0_inst", LatencyTier.TIER_0) {
                delay(15)
                controller.onLinkEmitted(createLink("Movie 1080p", Qualities.P1080.value))
            },
            PipelinedTask("tier1_task", LatencyTier.TIER_1) {
                tier1Executed.set(true)
            },
            PipelinedTask("tier2_task", LatencyTier.TIER_2) {
                tier2Executed.set(true)
            },
            PipelinedTask("tier3_task", LatencyTier.TIER_3) {
                tier3Executed.set(true)
            }
        )

        val start = System.currentTimeMillis()
        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )
        val duration = System.currentTimeMillis() - start

        assertTrue("Pipeliner should return true", result)
        assertTrue("Controller must be satisfied", controller.isSatisfied())
        assertFalse("Tier 2 task must be skipped", tier2Executed.get())
        assertFalse("Tier 3 task must be skipped", tier3Executed.get())
        assertTrue("Pipeline duration must be prompt on Tier 0 early satisfaction (was ${duration}ms)", duration < 500L)
    }

    @Test
    fun testSkippingTier3WhenEarlierTiersYieldLinks() = runBlocking {
        val tier3Executed = AtomicBoolean(false)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 4, // Not satisfied by 1 link
            minQualityStreams = 2,
            tier1DelayMs = 20L,
            tier2DelayMs = 50L,
            tier3DelayMs = 250L
        )
        val controller = EarlySatisfactionController(config)

        val tasks = listOf(
            PipelinedTask("tier1_task", LatencyTier.TIER_1) {
                delay(10)
                controller.onLinkEmitted(createLink("Standard 720p", Qualities.P720.value))
            },
            PipelinedTask("tier3_fallback", LatencyTier.TIER_3) {
                tier3Executed.set(true)
            }
        )

        SpeculativePipeliner.executePipelined(tasks, config, controller)

        assertEquals("Should have acquired 1 link", 1, controller.getLinksCount())
        assertFalse("Tier 3 should be skipped because earlier tiers yielded > 0 links", tier3Executed.get())
    }

    @Test
    fun testConcurrencyStrictlyBounded() = runBlocking {
        val currentRunning = AtomicInteger(0)
        val peakRunning = AtomicInteger(0)

        val tasks = (1..18).map { id ->
            PipelinedTask("task_$id", LatencyTier.TIER_0) {
                val cur = currentRunning.incrementAndGet()
                peakRunning.updateAndGet { prev -> maxOf(prev, cur) }
                delay(30)
                currentRunning.decrementAndGet()
            }
        }

        SpeculativePipeliner.executePipelined(
            tasks = tasks,
            maxConcurrencyOverride = 4
        )

        assertEquals(0, currentRunning.get())
        assertTrue("Peak running (${peakRunning.get()}) must be <= 4", peakRunning.get() <= 4)
        assertTrue("Peak running (${peakRunning.get()}) should reach concurrency limit", peakRunning.get() >= 3)
    }

    @Test
    fun testExceptionIsolationAcrossSiblingTasks() = runBlocking {
        val config = EarlySatisfactionConfig(minVerifiedLinks = 1)
        val controller = EarlySatisfactionController(config)

        val tasks = listOf(
            PipelinedTask("failing_task", LatencyTier.TIER_0) {
                throw IllegalStateException("Simulated network crash")
            },
            PipelinedTask("healthy_task", LatencyTier.TIER_0) {
                delay(10)
                controller.onLinkEmitted(createLink("Recovered 720p", Qualities.P720.value))
            }
        )

        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        assertTrue("Execution should succeed despite sibling exception", result)
        assertEquals(1, controller.getLinksCount())
    }

    @Test
    fun testEmptyTasksReturnsFalseImmediately() = runBlocking {
        val start = System.currentTimeMillis()
        val result = SpeculativePipeliner.executePipelined(emptyList())
        val duration = System.currentTimeMillis() - start

        assertFalse(result)
        assertTrue("Empty tasks should return in < 20ms", duration < 20L)
    }

    @Test
    fun testInFlightHigherPriorityTaskNotCancelledOnLowerPriorityEarlySatisfaction() = runBlocking {
        val higherPriorityFinished = AtomicBoolean(false)
        val lowerPriorityFinished = AtomicBoolean(false)
        val lowerTierSiblingCancelled = AtomicBoolean(false)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 1,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P1080.value,
            tier1DelayMs = 0L,
            tier2DelayMs = 0L,
            tier3DelayMs = 500L
        )
        val controller = EarlySatisfactionController(config)

        val tasks = listOf(
            // High priority task (e.g. VidLink - score 100) takes longer to finish (80ms)
            PipelinedTask("vidlink", LatencyTier.TIER_0, isVideo = true, priorityBoost = 100f) {
                delay(80)
                controller.onLinkEmitted(createLink("Vidlink 1080p", Qualities.P1080.value))
                higherPriorityFinished.set(true)
            },
            // Lower priority task (e.g. VidFast - score 70) finishes quickly (20ms) and satisfies controller
            PipelinedTask("vidfast", LatencyTier.TIER_0, isVideo = true, priorityBoost = 70f) {
                delay(20)
                controller.onLinkEmitted(createLink("Vidfast 1080p", Qualities.P1080.value))
                lowerPriorityFinished.set(true)
            },
            // Even lower priority task (e.g. VidEasy - score 60) should be cancelled
            PipelinedTask("videasy", LatencyTier.TIER_0, isVideo = true, priorityBoost = 60f) {
                try {
                    delay(120)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    lowerTierSiblingCancelled.set(true)
                    throw e
                }
            }
        )

        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        assertTrue("Execution should return true", result)
        assertTrue("Lower priority task should finish", lowerPriorityFinished.get())
        assertTrue("Higher priority task must NOT be aborted and must finish to completion", higherPriorityFinished.get())
        assertTrue("Lower/equal priority sibling should be cancelled", lowerTierSiblingCancelled.get())
    }

    @Test
    fun testSoftGracePeriodDoesNotAbortInFlightHigherPriorityTask() = runBlocking {
        val higherPriorityFinished = AtomicBoolean(false)
        val lowerPriorityFinished = AtomicBoolean(false)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 1,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P1080.value,
            softGracePeriodAfterFirstLinkMs = 30L, // Short grace period to fire while VidLink is in flight
            tier1DelayMs = 0L,
            tier2DelayMs = 0L
        )
        val controller = EarlySatisfactionController(config)

        val tasks = listOf(
            // High priority task (VidLink - score 100) takes longer (80ms)
            PipelinedTask("vidlink", LatencyTier.TIER_0, isVideo = true, priorityBoost = 100f) {
                delay(80)
                controller.onLinkEmitted(createLink("Vidlink 1080p", Qualities.P1080.value))
                higherPriorityFinished.set(true)
            },
            // Lower priority task (VidFast - score 70) finishes at 10ms and satisfies controller
            PipelinedTask("vidfast", LatencyTier.TIER_0, isVideo = true, priorityBoost = 70f) {
                delay(10)
                controller.onLinkEmitted(createLink("Vidfast 1080p", Qualities.P1080.value))
                lowerPriorityFinished.set(true)
            }
        )

        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        assertTrue("Execution should return true", result)
        assertTrue("Lower priority task should finish", lowerPriorityFinished.get())
        assertTrue("Higher priority task must finish even after soft grace period expires", higherPriorityFinished.get())
    }
}
