package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Empirical Challenger Adversarial Test Suite for Milestone 2.
 * 
 * Verifies:
 * 1. Adversarial cancellation lifecycle across extractors (immediate abort & CancellationException propagation).
 * 2. Unbreakable quality hierarchy preservation (720p > 1080p > 480p > UHD) under adversarial metadata spoofing.
 * 3. Dual-quality deduplication integrity for M3U8 multi-variants vs direct MP4 byte-streams.
 * 4. High-concurrency cancellation storms without semaphore permit depletion or deadlocks.
 */
class ChallengerM2EmpiricalAdversarialTest {

    @Before
    fun setUp() {
        // Pre-warm class loading so JVM cold-start classloader does not distort coroutine cancellation benchmarks
        StreamPlayExtractor.name
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    private fun createLink(
        source: String,
        name: String,
        url: String,
        quality: Int = Qualities.P1080.value,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        referer: String = "https://example.com/",
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = referer,
            quality = quality,
            type = type,
            headers = headers
        )
    }

    // =========================================================================
    // 1. ADVERSARIAL CANCELLATION & EXCEPTION PROPAGATION
    // =========================================================================

    @Test
    fun testSotaExtractorsCancellationStormPreservesSemaphores() = runBlocking {
        val initialPermits = StreamPlayExtractor.encDecApiSemaphore.availablePermits
        assertEquals("Initial semaphore permits must be 4", 4, initialPermits)

        val iterations = 25
        val cancelledCounter = AtomicInteger(0)

        repeat(iterations) { idx ->
            val job = launch(Dispatchers.IO) {
                try {
                    when (idx % 4) {
                        0 -> StreamPlayExtractor.invokeVidlink(
                            tmdbId = 550, season = null, episode = null, subtitleCallback = null, callback = {}
                        )
                        1 -> StreamPlayExtractor.invokeCineby(
                            tmdbId = 550, season = null, episode = null, callback = {}
                        )
                        2 -> StreamPlayExtractor.invokeCineSrc(
                            tmdbId = 550, season = null, episode = null, subtitleCallback = null, callback = {}
                        )
                        else -> StreamPlayExtractor.invokeVidSrcSU(
                            tmdbId = 550, season = null, episode = null, callback = {}
                        )
                    }
                } catch (e: CancellationException) {
                    cancelledCounter.incrementAndGet()
                    throw e
                }
            }

            delay(15)
            job.cancelAndJoin()
        }

        assertTrue("Cancellation exceptions caught in storm", cancelledCounter.get() > 0)
        assertEquals(
            "encDecApiSemaphore permits must be fully restored after cancellation storm",
            4,
            StreamPlayExtractor.encDecApiSemaphore.availablePermits
        )
    }

    // =========================================================================
    // 2. UNBREAKABLE QUALITY HIERARCHY UNDER ADVERSARIAL METADATA SPOOFING
    // =========================================================================

    @Test
    fun testAdversarial1080pMetadataCannotInvert720pHierarchy() {
        // Construct an adversarial 1080p stream with every possible score-boosting badge
        val spoofed1080 = createLink(
            source = "VidLink",
            name = "VidLink [1080p] [100000kbps] [REMUX] [IMAX] [DV] [HDR10+] [HEVC] [AV1] [10-bit] [TrueHD] [Atmos] [7.1] [Dual Audio] [Multi Audio]",
            url = "https://vidlink.pro/master_ultra.m3u8",
            quality = Qualities.P1080.value,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Accept" to "*/*",
                "Accept-Encoding" to "identity",
                "Connection" to "keep-alive"
            )
        )

        // Construct a bare minimum 720p stream from a rank-0 secondary provider (UnknownCDN)
        val bareUnknownCDN720 = createLink(
            source = "UnknownCDN",
            name = "UnknownCDN [720p]",
            url = "https://yflix.to/bare_stream.m3u8",
            quality = Qualities.P720.value
        )

        val scoreSpoofed1080 = StreamLinkOptimizer.getStreamCompositeScore(spoofed1080)
        val scoreBare720 = StreamLinkOptimizer.getStreamCompositeScore(bareUnknownCDN720)

        // SOURCE RANK IS PRIMARY: the top-tier VidLink 1080p always outranks a rank-0
        // secondary 720p (VidLink 100 x 8,000 = 1,008,000+, secondary 720p = 800.0).
        assertTrue(
            "Spoofed VidLink 1080p ($scoreSpoofed1080) must beat bare UnknownCDN 720p ($scoreBare720): source rank decides",
            scoreSpoofed1080 > scoreBare720
        )

        assertTrue(
            "isStreamBetter must return true for the higher-ranked 1080p",
            StreamLinkOptimizer.isStreamBetter(spoofed1080, bareUnknownCDN720)
        )
        assertFalse(
            "isStreamBetter must return false for the lower-ranked bare 720p",
            StreamLinkOptimizer.isStreamBetter(bareUnknownCDN720, spoofed1080)
        )

        val sorted = listOf(spoofed1080, bareUnknownCDN720).sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals("Comparator must sort the higher-ranked 1080p first", spoofed1080, sorted[0])
        assertEquals("Comparator must sort the secondary 720p second", bareUnknownCDN720, sorted[1])

        // WITHIN one source the metadata spoofing is still powerless: a bare VidLink 720p
        // must beat the maximally badged VidLink 1080p.
        val bareVidLink720 = createLink(
            source = "VidLink",
            name = "VidLink [720p]",
            url = "https://vidlink.pro/bare_stream.m3u8",
            quality = Qualities.P720.value
        )
        val scoreBareVidLink720 = StreamLinkOptimizer.getStreamCompositeScore(bareVidLink720)

        assertTrue(
            "Bare VidLink 720p ($scoreBareVidLink720) must still beat spoofed VidLink 1080p ($scoreSpoofed1080) within the same source",
            scoreBareVidLink720 > scoreSpoofed1080
        )
        assertTrue(
            "isStreamBetter must return true for the bare 720p over the spoofed 1080p of the same source",
            StreamLinkOptimizer.isStreamBetter(bareVidLink720, spoofed1080)
        )
        assertFalse(
            "isStreamBetter must return false for the spoofed 1080p over the bare 720p of the same source",
            StreamLinkOptimizer.isStreamBetter(spoofed1080, bareVidLink720)
        )

        val sortedSameSource = listOf(spoofed1080, bareVidLink720).sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals("Comparator must sort bare 720p first", bareVidLink720, sortedSameSource[0])
        assertEquals("Comparator must sort spoofed 1080p second", spoofed1080, sortedSameSource[1])
    }

    @Test
    fun testAllQualitiesMonotonicPriorityVerification() {
        val qualities = listOf(
            Qualities.P720.value to 10000,
            Qualities.P1080.value to 9000,
            Qualities.P480.value to 7000,
            576 to 6500,
            Qualities.P360.value to 5360,
            Qualities.P1440.value to 3640,
            Qualities.P2160.value to 2920,
            Qualities.Unknown.value to 0
        )

        for (i in 0 until qualities.size - 1) {
            val (qCurrent, expectedCurrent) = qualities[i]
            val (qNext, expectedNext) = qualities[i + 1]

            val scoreCurrent = StreamLinkOptimizer.getQualityPriorityScore(qCurrent)
            val scoreNext = StreamLinkOptimizer.getQualityPriorityScore(qNext)

            assertEquals("Score for quality $qCurrent", expectedCurrent, scoreCurrent)
            assertEquals("Score for quality $qNext", expectedNext, scoreNext)
            assertTrue("Quality $qCurrent must strictly score higher than $qNext", scoreCurrent > scoreNext)
        }
    }

    // =========================================================================
    // 3. DUAL QUALITY DEDUPLICATION & STREAM INTEGRITY
    // =========================================================================

    @Test
    fun testM3u8MultiVariantDeduplicatorPreservesBoth720pAnd1080pWithCleanUrls() {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedLinks.add(it) }

        val cleanMasterUrl = "https://cdn.example.com/hls/master.m3u8"
        val link1080 = createLink("UnknownCDN", "UnknownCDN [1080p]", cleanMasterUrl, Qualities.P1080.value, ExtractorLinkType.M3U8)
        val link720 = createLink("UnknownCDN", "UnknownCDN [720p]", cleanMasterUrl, Qualities.P720.value, ExtractorLinkType.M3U8)

        val res1080 = deduplicator.emitDetailed(link1080)
        val res720 = deduplicator.emitDetailed(link720)

        assertEquals("1080p M3U8 variant must be NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res1080)
        assertEquals("720p M3U8 variant must be NEW (retained, not dropped)", StreamLinkOptimizer.DeduplicationResult.NEW, res720)
        assertEquals("Deduplicator must emit exactly 2 links for multi-variant HLS", 2, deduplicator.getEmittedCount())
        assertEquals(cleanMasterUrl, emittedLinks[0].url)
        assertEquals(cleanMasterUrl, emittedLinks[1].url)
    }

    @Test
    fun testDirectVideoSingleFileUpgradedTo720pWithoutDuplication() {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedLinks.add(it) }

        val directMp4Url = "https://cdn.example.com/files/movie.mp4"
        val link1080 = createLink("UnknownSource", "UnknownSource [1080p]", directMp4Url, Qualities.P1080.value, ExtractorLinkType.VIDEO)
        val link720 = createLink("UnknownSource", "UnknownSource [720p]", directMp4Url, Qualities.P720.value, ExtractorLinkType.VIDEO)

        val res1080 = deduplicator.emitDetailed(link1080)
        val res720 = deduplicator.emitDetailed(link720)

        assertEquals("First MP4 stream is NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res1080)
        assertEquals("Second MP4 with 720p upgrades 1080p", StreamLinkOptimizer.DeduplicationResult.UPGRADED, res720)
        assertEquals("Deduplicator must emit exactly 1 final stream for direct MP4", 1, deduplicator.getEmittedCount())
        assertEquals("Preserved stream must be 720p", Qualities.P720.value, deduplicator.getEmittedLinks().first().quality)
    }

    // =========================================================================
    // 4. PRIORITY STREAM DISPATCHER STAGING & FLUSH LIFECYCLE
    // =========================================================================

    @Test
    fun testPriorityStreamDispatcherStrictStagingSequence() = runBlocking {
        val dispatched = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            scope = this,
            stageWindowMs = 250L,
            subtitleGraceMs = 100L,
            topSourceGraceMs = 100L,
            fhdGraceMs = 150L,
            sdGraceMs = 200L,
            upstreamCallback = { dispatched.add(it) }
        )

        dispatcher.onSubtitleReceived()

        val vidup720 = createLink("ExampleCDN", "ExampleCDN [720p]", "https://vidup.io/720.m3u8", Qualities.P720.value)
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val animex1080 = createLink("Animex", "Animex [1080p]", "https://animex.one/1080.m3u8", Qualities.P1080.value)
        val vidlink480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)

        // Lower-tier 720p arrives first -> staged waiting for pinnacle VidLink (rank 100)
        dispatcher.onLinkAccepted(vidup720)
        assertTrue("ExampleCDN 720p staged waiting for top source grace period", dispatched.isEmpty())

        // Pinnacle VidLink 720p arrives within grace period -> dispatched as #1 immediately!
        delay(20)
        dispatcher.onLinkAccepted(vidlink720)
        // VidLink 720p is emitted as #1, unblocking staged ExampleCDN 720p as #2
        assertEquals("Both 720p streams must be emitted in rank priority order", 2, dispatched.size)
        assertEquals("VidLink 720p (rank 100) must be first", vidlink720, dispatched[0])
        assertEquals("ExampleCDN 720p (rank 0) must be second", vidup720, dispatched[1])

        // 480p arrives before 1080p -> staged waiting for 1080p
        dispatcher.onLinkAccepted(vidlink480)
        assertEquals("480p must not be emitted before 1080p", 2, dispatched.size)

        // 1080p arrives -> emitted as #3, flushes 480p as #4
        dispatcher.onLinkAccepted(animex1080)
        assertEquals("1080p emitted and flushes 480p", 4, dispatched.size)
        assertEquals("Animex 1080p must be third", animex1080, dispatched[2])
        assertEquals("VidLink 480p must be fourth", vidlink480, dispatched[3])

        dispatcher.flush()
    }

    @Test
    fun testConcurrentStreamsDualQualitySpoofedMetadataTiebreaker() = runBlocking {
        val iterations = 10
        repeat(iterations) {
            val spoofed1080 = createLink(
                source = "VidLink",
                name = "VidLink [1080p] [100000kbps] [REMUX] [IMAX] [DV] [HDR10+] [HEVC] [AV1] [10-bit] [TrueHD] [Atmos]",
                url = "https://cdn.example.com/hls/master.m3u8",
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.M3U8
            )
            val bare720 = createLink(
                source = "VidLink",
                name = "VidLink [720p]",
                url = "https://cdn.example.com/hls/master.m3u8",
                quality = Qualities.P720.value,
                type = ExtractorLinkType.M3U8
            )

            val jobs = (1..10).map { threadIdx ->
                launch(Dispatchers.Default) {
                    val localEmitted = mutableListOf<ExtractorLink>()
                    val input = if (threadIdx % 2 == 0) listOf(spoofed1080, bare720) else listOf(bare720, spoofed1080)
                    StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
                        source = "UnknownSource",
                        baseName = "UnknownSource",
                        url = "https://cdn.example.com/hls/master.m3u8",
                        referer = "https://cdn.example.com/",
                        generatedLinks = input
                    ) { link ->
                        localEmitted.add(link)
                    }
                    assertEquals("Must emit exactly 2 links", 2, localEmitted.size)
                    assertEquals("First emitted link must always be 720p", Qualities.P720.value, localEmitted[0].quality)
                    assertEquals("Second emitted link must always be 1080p", Qualities.P1080.value, localEmitted[1].quality)
                }
            }
            jobs.joinAll()
        }
    }
}
