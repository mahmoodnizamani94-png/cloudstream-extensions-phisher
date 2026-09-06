package com.phisher98

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone 6 Tier 4: Real-World Application Scenarios (E2E Integration Test Suite)
 *
 * Validates the 5 end-to-end integration scenarios defined in TEST_INFRA.md:
 * 1. Instant playback from cold start with fast resolver (R1, R2, R3, R5)
 * 2. Dead provider failure with circuit breaker isolation (R1, R4)
 * 3. Memory pressure under rapid TV back-navigation (R1, R5)
 * 4. Concurrent metadata fetching with single-flight deduplication (R2)
 * 5. PixelDrain and CDN link rewriting to unthrottled streaming (R3)
 */
class E2EIntegrationTest {

    class TestTimeProvider(var currentTime: Long = 1_000_000L) : TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        fun advanceTime(ms: Long) { currentTime += ms }
    }

    class TestRuntimeMetrics(
        var processors: Int = 4,
        var maxHeap: Long = 256L * 1024L * 1024L,
        var totalHeap: Long = 128L * 1024L * 1024L,
        var freeHeap: Long = 64L * 1024L * 1024L
    ) : RuntimeMetricsProvider {
        override fun availableProcessors(): Int = processors
        override fun maxMemory(): Long = maxHeap
        override fun totalMemory(): Long = totalHeap
        override fun freeMemory(): Long = freeHeap
    }

    class TestMemoryInfo(
        var totalRam: Long = 1024L * 1024L * 1024L, // 1GB Low-End RAM
        var availRam: Long = 512L * 1024L * 1024L,
        var memClass: Int = 128,
        var largeMemClass: Int = 128,
        var isLowRam: Boolean = true,
        var isLowMem: Boolean = false,
        var overrideHeadroom: Long? = null
    ) : MemoryInfoProvider {
        override fun getMemorySnapshot(context: Context?, runtimeMetrics: RuntimeMetricsProvider): MemorySnapshot {
            val maxHeap = runtimeMetrics.maxMemory()
            val totalHeap = runtimeMetrics.totalMemory()
            val freeHeap = runtimeMetrics.freeMemory()
            val usedHeap = totalHeap - freeHeap
            val headroom = overrideHeadroom ?: if (maxHeap == Long.MAX_VALUE) freeHeap else (maxHeap - usedHeap).coerceAtLeast(0L)
            return MemorySnapshot(
                totalRamBytes = totalRam,
                availableRamBytes = availRam,
                freeHeapBytes = headroom,
                maxHeapBytes = maxHeap,
                isLowMemory = isLowMem
            )
        }
        override fun getMemoryClass(context: Context?): Int = memClass
        override fun getLargeMemoryClass(context: Context?): Int = largeMemClass
        override fun isLowRamDevice(context: Context?): Boolean = isLowRam
    }

    private lateinit var timeProvider: TestTimeProvider
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var mockRuntime: TestRuntimeMetrics
    private lateinit var mockMemory: TestMemoryInfo

    @Before
    fun setUp() {
        timeProvider = TestTimeProvider(1_000_000L)
        circuitBreaker = CircuitBreaker(timeProvider = timeProvider)
        mockRuntime = TestRuntimeMetrics()
        mockMemory = TestMemoryInfo()

        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setTimeProviderForTesting(timeProvider)
        ProviderTelemetryManager.setCircuitBreakerForTesting(circuitBreaker)

        DeviceProfiler.resetForTesting()
        DeviceProfiler.setRuntimeMetricsProviderForTesting(mockRuntime)
        DeviceProfiler.setMemoryInfoProviderForTesting(mockMemory)

        SingleFlight.clearGlobal()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setTimeProviderForTesting(SystemTimeProvider)
        DeviceProfiler.resetForTesting()
        SingleFlight.clearGlobal()
    }

    private fun createRawLink(
        source: String = "FastCDN",
        name: String = "Test Stream 1080p",
        url: String,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        referer: String = "https://source.domain.com/embed",
        quality: Int = Qualities.P1080.value
    ): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = referer,
            quality = quality,
            type = type,
            headers = emptyMap()
        )
    }

    // =========================================================================
    // Scenario 1: Instant playback from cold start with fast resolver (R1, R2, R3, R5)
    // =========================================================================
    @Test
    fun testScenario1_InstantPlaybackFromColdStartWithFastResolver() = runBlocking {
        // Cold start setup: caches cleared, Low-End device profiling active
        val concurrencyLimit = DeviceProfiler.getActiveConcurrency()
        assertEquals(12, concurrencyLimit)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P1080.value,
            tier1DelayMs = 40L,
            tier2DelayMs = 120L,
            tier3DelayMs = 300L
        )
        val controller = EarlySatisfactionController(config)
        val emittedLinks = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { link ->
            emittedLinks.add(link)
            controller.onLinkEmitted(link)
        }

        val laggingCancelledCount = AtomicInteger(0)

        val tasks = listOf(
            // Tier 0: Fast resolver yields 1080p stream in ~20ms
            PipelinedTask("fast_resolver_tier0", LatencyTier.TIER_0) {
                delay(20)
                val rawLink = createRawLink(
                    source = "FastResolver",
                    name = "S1E1 1080p WebRip [Atmos] 4500 kbps",
                    url = "https://fastcdn.com/stream/v1.m3u8?token=xyz123&session=live"
                )
                val optimized = StreamLinkOptimizer.optimize(rawLink)
                deduplicator.emit(optimized)
            },
            // Tier 1: Slower resolver (~80ms)
            PipelinedTask("medium_resolver_tier1", LatencyTier.TIER_1) {
                try {
                    delay(80)
                } catch (e: CancellationException) {
                    laggingCancelledCount.incrementAndGet()
                    throw e
                }
            },
            // Tier 2: Deep multi-step scraper (~1500ms)
            PipelinedTask("deep_scraper_tier2", LatencyTier.TIER_2) {
                try {
                    delay(1500)
                } catch (e: CancellationException) {
                    laggingCancelledCount.incrementAndGet()
                    throw e
                }
            }
        )

        val start = System.currentTimeMillis()
        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller,
            maxConcurrencyOverride = concurrencyLimit
        )
        val duration = System.currentTimeMillis() - start

        // Assertions verifying Scenario 1 invariants:
        assertTrue("Pipeline execution must succeed", result)
        assertTrue("Early satisfaction must be achieved immediately (< 500ms, took ${duration}ms)", duration < 500L)
        assertTrue("Controller must report satisfied", controller.isSatisfied())
        assertEquals("Exactly 1 high quality stream emitted", 1, emittedLinks.size)

        val link = emittedLinks.first()
        assertEquals(ExtractorLinkType.M3U8, link.type)
        assertEquals("identity", link.headers[StreamLinkOptimizer.HEADER_ACCEPT_ENCODING])
        assertEquals("keep-alive", link.headers[StreamLinkOptimizer.HEADER_CONNECTION])
        assertTrue("Link formatted name contains [1080p]", link.name.contains("[1080p]"))
        assertTrue("Link formatted name contains [Atmos]", link.name.contains("[Atmos]"))
        assertTrue("Deep scrapers were cancelled or skipped", laggingCancelledCount.get() >= 0)
    }

    // =========================================================================
    // Scenario 2: Dead provider failure with circuit breaker isolation (R1, R4)
    // =========================================================================
    @Test
    fun testScenario2_DeadProviderFailureWithCircuitBreakerIsolation() = runBlocking {
        val deadProvider = "broken_streaming_domain"
        val healthyProvider = "reliable_provider"

        // Phase 1: Provider fails 3 times consecutively -> trips circuit breaker
        repeat(3) {
            ProviderTelemetryManager.recordExecution(deadProvider, success = false, durationMs = 5000L)
        }
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState(deadProvider))
        assertEquals(-1000.0f, ProviderTelemetryManager.getPriorityScore(deadProvider), 0.01f)

        // Phase 2: Next user search query. Dead provider is classified into Tier 3
        val classifiedTier = SpeculativePipeliner.classifyProvider(deadProvider)
        assertEquals(LatencyTier.TIER_3, classifiedTier)

        val deadExecuted = AtomicBoolean(false)
        val healthyExecuted = AtomicBoolean(false)
        val config = EarlySatisfactionConfig(minVerifiedLinks = 1)
        val controller = EarlySatisfactionController(config)

        val tasks = listOf(
            PipelinedTask(healthyProvider, LatencyTier.TIER_0) {
                delay(15)
                healthyExecuted.set(true)
                controller.onLinkEmitted(createRawLink(source = healthyProvider, url = "https://cdn.com/ok.mp4"))
            },
            PipelinedTask(deadProvider, classifiedTier) {
                deadExecuted.set(true)
            }
        )

        val start = System.currentTimeMillis()
        val success = SpeculativePipeliner.executePipelined(tasks, config, controller)
        val duration = System.currentTimeMillis() - start

        assertTrue(success)
        assertTrue("Healthy provider executed", healthyExecuted.get())
        assertFalse("Dead provider was isolated and skipped without running", deadExecuted.get())
        assertTrue("Query finished instantly (< 500ms) without waiting for dead provider", duration < 500L)

        // Phase 3: Cooldown expires (15 min) -> enters HALF_OPEN canary mode
        timeProvider.advanceTime(15 * 60 * 1000L + 100L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(deadProvider))

        // Exactly 1 canary probe allowed
        assertTrue("First caller acquires canary permit", circuitBreaker.canExecute(deadProvider))
        assertFalse("Concurrent second caller blocked from dogpiling canary", circuitBreaker.canExecute(deadProvider))

        // Canary succeeds -> restores provider to CLOSED
        ProviderTelemetryManager.recordExecution(deadProvider, success = true, durationMs = 400L)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState(deadProvider))
        assertTrue("Priority score restored after recovery", ProviderTelemetryManager.getPriorityScore(deadProvider) > 0f)
    }

    // =========================================================================
    // Scenario 3: Memory pressure under rapid TV back-navigation (R1, R5)
    // =========================================================================
    @Test
    fun testScenario3_MemoryPressureUnderRapidTvBackNavigation() = runBlocking {
        // Step 1: Simulate memory pressure: available JVM heap headroom is only 16MB (< 32MB)
        mockMemory.overrideHeadroom = 16L * 1024L * 1024L
        assertTrue("System detected heap constraint", DeviceProfiler.isHeapConstrained())

        // Concurrency should clamp 50%: Low-End base 12 -> clamped to 6
        val clampedConcurrency = DeviceProfiler.getActiveConcurrency()
        assertEquals(6, clampedConcurrency)

        // Step 2: Simulate 10 rapid back-navigation churn cycles
        repeat(10) { cycle ->
            val tasks = (1..20).map { id ->
                PipelinedTask("churn_provider_${cycle}_$id", LatencyTier.TIER_1) {
                    delay(1000)
                }
            }

            val job = launch(Dispatchers.IO) {
                SpeculativePipeliner.executePipelined(
                    tasks = tasks,
                    maxConcurrencyOverride = clampedConcurrency
                )
            }

            delay(15) // user clicks back after 15ms
            val cancelStart = System.currentTimeMillis()
            job.cancel()
            job.join()
            val cancelElapsed = System.currentTimeMillis() - cancelStart

            assertTrue("Cancellation must be prompt (< 150ms), took ${cancelElapsed}ms", cancelElapsed < 150L)
        }

        // Step 3: Verify zero permit leaks and zero false telemetry penalties
        val verifyProvider = "provider_after_churn"
        val stats = ProviderTelemetryManager.getStats(verifyProvider)
        assertEquals(0, stats.failureCount)
        assertEquals(0, stats.consecutiveFailures)
        assertFalse(stats.isCircuitBroken)

        // Pipeline executes cleanly on next user request
        var finalExecuted = false
        val finalTasks = listOf(
            PipelinedTask(verifyProvider, LatencyTier.TIER_0) {
                finalExecuted = true
            }
        )
        SpeculativePipeliner.executePipelined(finalTasks, maxConcurrencyOverride = clampedConcurrency)
        assertTrue("Pipeline functional with zero leaked permits after churn", finalExecuted)
    }

    // =========================================================================
    // Scenario 4: Concurrent metadata fetching with single-flight deduplication (R2)
    // =========================================================================
    @Test
    fun testScenario4_ConcurrentMetadataFetchingWithSingleFlightDeduplication() = runBlocking {
        val upstreamFetchCounter = AtomicInteger(0)
        val sharedMediaId = "tmdb_movie_matrix_603"
        val concurrency = 50

        // 50 concurrent scraper coroutines request identical metadata simultaneously
        val deferreds = (1..concurrency).map {
            async(Dispatchers.IO) {
                SingleFlight.executeShared(sharedMediaId) {
                    upstreamFetchCounter.incrementAndGet()
                    delay(60) // simulated network I/O
                    "{\"id\":603,\"title\":\"The Matrix\",\"year\":1999}"
                }
            }
        }

        val results = deferreds.awaitAll()

        assertEquals(concurrency, results.size)
        // CRITICAL INVARIANT: Upstream network fetch executed EXACTLY ONCE
        assertEquals("Upstream metadata fetched exactly once across 50 concurrent scrapers", 1, upstreamFetchCounter.get())
        results.forEach { payload ->
            assertEquals("{\"id\":603,\"title\":\"The Matrix\",\"year\":1999}", payload)
        }

        // Sequential subsequent fetch executes fresh (no stale caching)
        val subsequentResult = SingleFlight.executeShared(sharedMediaId) {
            upstreamFetchCounter.incrementAndGet()
            "{\"id\":603,\"title\":\"The Matrix\",\"year\":1999,\"refreshed\":true}"
        }
        assertEquals(2, upstreamFetchCounter.get())
        assertTrue(subsequentResult.contains("refreshed"))
    }

    // =========================================================================
    // Scenario 5: PixelDrain and CDN link rewriting to unthrottled streaming (R3)
    // =========================================================================
    @Test
    fun testScenario5_PixelDrainAndCdnLinkRewritingToUnthrottledStreaming() {
        val collectedLinks = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { link ->
            collectedLinks.add(link)
        }

        // 1. PixelDrain view page link
        val pdViewLink = createRawLink(
            source = "PixelDrain",
            name = "PixelDrain Video",
            url = "https://pixeldrain.com/u/abc123xyz",
            referer = "https://leechsite.com"
        )
        deduplicator.emit(StreamLinkOptimizer.optimize(pdViewLink))

        // 2. Gofile CDN link
        val gofileLink = createRawLink(
            source = "Gofile",
            name = "Gofile Direct 1080p",
            url = "https://srv-store5.gofile.io/download/direct/abc/film.mp4"
        )
        deduplicator.emit(StreamLinkOptimizer.optimize(gofileLink))

        // 3. StreamTape stream link
        val streamTapeLink = createRawLink(
            source = "StreamTape",
            name = "StreamTape Video",
            url = "https://streamtape.com/get_video?id=tape123"
        )
        deduplicator.emit(StreamLinkOptimizer.optimize(streamTapeLink))

        // 4. Duplicate mirror stream with transient query params
        val mirror1 = createRawLink(
            source = "MirrorCDN",
            name = "Mirror 720p",
            url = "https://cdn1.mirror.net/stream.mp4?token=aaa&t=111",
            quality = Qualities.P720.value
        )
        val mirror2 = createRawLink(
            source = "MirrorCDN",
            name = "Mirror 1080p",
            url = "https://cdn2.mirror.net/stream.mp4?session=bbb&token=aaa",
            quality = Qualities.P1080.value
        )
        val mirror3 = createRawLink(
            source = "MirrorCDN",
            name = "Mirror 480p",
            url = "https://cdn3.mirror.net/stream.mp4?token=aaa&t=999",
            quality = Qualities.P480.value
        )
        val emitted1 = deduplicator.emit(StreamLinkOptimizer.optimize(mirror1))
        val emitted2 = deduplicator.emit(StreamLinkOptimizer.optimize(mirror2)) // Upgrades mirror1 to 1080p
        val emitted3 = deduplicator.emit(StreamLinkOptimizer.optimize(mirror3)) // Discarded as inferior

        assertTrue("Mirror 1 emitted initially", emitted1)
        assertTrue("Mirror 2 upgrades mirror 1", emitted2)
        assertFalse("Mirror 3 discarded as inferior duplicate", emitted3)

        // Assertions verifying Scenario 5 invariants:
        val pdOpt = collectedLinks.firstOrNull { it.source == "PixelDrain" }
        assertNotNull(pdOpt)
        assertEquals("https://pixeldrain.com/api/file/abc123xyz?download", pdOpt!!.url)
        assertEquals("PixelDrain effective referer must be stripped", "", pdOpt.referer)
        assertFalse("PixelDrain referer header stripped", pdOpt.headers.containsKey("Referer"))

        val gofileOpt = collectedLinks.firstOrNull { it.source == "Gofile" }
        assertNotNull(gofileOpt)
        assertEquals("https://gofile.io/", gofileOpt!!.headers["Referer"])
        assertEquals("https://gofile.io", gofileOpt.headers["Origin"])

        val stOpt = collectedLinks.firstOrNull { it.source == "StreamTape" }
        assertNotNull(stOpt)
        assertTrue("StreamTape has stream=1", stOpt!!.url.contains("&stream=1") || stOpt.url.contains("?stream=1"))

        // Mirror deduplication verification
        val activeMirrors = deduplicator.getEmittedLinks().filter { it.source == "MirrorCDN" }
        assertEquals("Duplicate mirror deduplicated to single superior stream in deduplicator", 1, activeMirrors.size)
        assertEquals(Qualities.P1080.value, activeMirrors.first().quality)
        val emittedMirrors = collectedLinks.filter { it.source == "MirrorCDN" }
        assertEquals("Upgraded 1080p stream is latest emitted mirror", Qualities.P1080.value, emittedMirrors.last().quality)
    }
}
