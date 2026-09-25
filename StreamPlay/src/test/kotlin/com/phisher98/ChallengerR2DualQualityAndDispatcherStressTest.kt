package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial empirical stress-test harness for StreamPlay Milestone 2.
 * Validates:
 * 1. Strict mathematical dual-quality separation across all tiebreaker permutations.
 * 2. Monotonic ranking across all top-tier and fallback providers.
 * 3. PriorityStreamDispatcher concurrency, grace periods, race conditions, and thread safety.
 */
class ChallengerR2DualQualityAndDispatcherStressTest {

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
    // 1. DUAL-QUALITY SCORING EMPIRICAL MATHEMATICAL SEPARATION
    // =========================================================================

    @Test
    fun testMathematicalSeparationAcrossAllTiebreakerPermutations() {
        // Minimal possible 720p stream: Lowest top-tier provider (VidEasy, rank 70), 0 bitrate, 0 badges, 0 headers
        val minVidEasy720 = createLink(
            source = "VidEasy",
            name = "VidEasy [720p]",
            url = "https://player.videasy.to/stream_minimal.m3u8",
            quality = Qualities.P720.value
        )
        val minScore720 = StreamLinkOptimizer.getStreamCompositeScore(minVidEasy720)
        assertEquals("Lowest top-tier 720p score must be exactly 10,700.0f", 10700.0f, minScore720, 0.001f)

        // Maximal possible 1080p stream: Pinnacle top-tier provider (VidLink, rank 100), max bitrate (50k), all video badges, all audio badges, all headers
        val maxHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "video"
        )
        val maxVidLink1080 = createLink(
            source = "VidLink",
            name = "VidLink [1080p] [50000kbps] [REMUX] [DV] [HEVC] [AV1] [10-bit] [TrueHD] [Atmos] [7.1] [Dual Audio] [Multi Audio]",
            url = "https://vidlink.pro/stream_maximal.m3u8",
            quality = Qualities.P1080.value,
            headers = maxHeaders
        )
        val maxScore1080 = StreamLinkOptimizer.getStreamCompositeScore(maxVidLink1080)
        assertEquals("Maximal top-tier 1080p score must be exactly 9,009.9f", 9009.9f, maxScore1080, 0.001f)

        val separationMargin = minScore720 - maxScore1080
        assertTrue(
            "Separation margin must be at least 1,690.0f (observed: $separationMargin)",
            separationMargin >= 1690.0f
        )
        assertTrue(StreamLinkOptimizer.isStreamBetter(minVidEasy720, maxVidLink1080))

        // Permute all top-tier providers across 720p vs 1080p with randomized or edge-case badge sets
        val providers = listOf(
            "VidLink" to 100,
            "Vidcore" to 95,
            "Vidup" to 92,
            "RiveStream" to 90,
            "CineJoy" to 88,
            "Peachify" to 80,
            "VidEasy" to 70
        )

        val sampleBitrates = listOf(0, 1000, 5000, 10000, 25000, 50000, 100000)
        val videoTags = listOf("", "[REMUX]", "[BluRay] [HDR10+]", "[WEBRip] [DV] [HEVC] [10-bit]")
        val audioTags = listOf("", "[TrueHD]", "[DTS-HD] [7.1]", "[Atmos] [5.1] [Dual Audio]")

        for ((p720Name, _) in providers) {
            val link720 = createLink(
                source = p720Name,
                name = "$p720Name [720p]",
                url = "https://$p720Name.com/720.m3u8",
                quality = Qualities.P720.value
            )
            val score720 = StreamLinkOptimizer.getStreamCompositeScore(link720)

            for ((p1080Name, _) in providers) {
                for (bitrate in sampleBitrates) {
                    for (vTag in videoTags) {
                        for (aTag in audioTags) {
                            val link1080 = createLink(
                                source = p1080Name,
                                name = "$p1080Name [1080p] [${bitrate}kbps] $vTag $aTag",
                                url = "https://$p1080Name.com/1080.m3u8",
                                quality = Qualities.P1080.value,
                                headers = maxHeaders
                            )
                            val score1080 = StreamLinkOptimizer.getStreamCompositeScore(link1080)
                            assertTrue(
                                "EVERY 720p ($p720Name: $score720) must strictly beat EVERY 1080p ($p1080Name: $score1080)",
                                score720 > score1080
                            )
                            assertTrue(StreamLinkOptimizer.isStreamBetter(link720, link1080))
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2. MONOTONIC ORDERING WITHIN THE SAME TIER
    // =========================================================================

    @Test
    fun testMonotonicProviderOrderingWithMaximalAdversarialTiebreakers() {
        val providersInOrder = listOf(
            "VidLink" to 100,
            "Vidcore" to 95,
            "Vidup" to 92,
            "RiveStream" to 90,
            "CineJoy" to 88,
            "Peachify" to 80,
            "VidEasy" to 70
        )

        val maxHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "video"
        )

        // For each adjacent pair (higher, lower), ensure that EVEN IF lower has MAXIMUM tiebreaker (9.9f)
        // and higher has MINIMUM tiebreaker (0.0f), higher strictly outscores lower.
        for (i in 0 until providersInOrder.size - 1) {
            val (higherName, higherRank) = providersInOrder[i]
            val (lowerName, lowerRank) = providersInOrder[i + 1]

            // 720p comparison
            val higher720Min = createLink(higherName, "$higherName [720p]", "https://$higherName.com/720.m3u8", Qualities.P720.value)
            val lower720Max = createLink(
                lowerName,
                "$lowerName [720p] [50000kbps] [REMUX] [DV] [TrueHD] [7.1]",
                "https://$lowerName.com/720.m3u8",
                Qualities.P720.value,
                headers = maxHeaders
            )

            val sHigh720 = StreamLinkOptimizer.getStreamCompositeScore(higher720Min)
            val sLow720 = StreamLinkOptimizer.getStreamCompositeScore(lower720Max)

            assertTrue(
                "Higher rank provider $higherName ($higherRank: $sHigh720) must strictly beat $lowerName ($lowerRank: $sLow720) at 720p despite maximal tiebreakers",
                sHigh720 > sLow720
            )

            // 1080p comparison
            val higher1080Min = createLink(higherName, "$higherName [1080p]", "https://$higherName.com/1080.m3u8", Qualities.P1080.value)
            val lower1080Max = createLink(
                lowerName,
                "$lowerName [1080p] [50000kbps] [REMUX] [DV] [TrueHD] [7.1]",
                "https://$lowerName.com/1080.m3u8",
                Qualities.P1080.value,
                headers = maxHeaders
            )

            val sHigh1080 = StreamLinkOptimizer.getStreamCompositeScore(higher1080Min)
            val sLow1080 = StreamLinkOptimizer.getStreamCompositeScore(lower1080Max)

            assertTrue(
                "Higher rank provider $higherName ($higherRank: $sHigh1080) must strictly beat $lowerName ($lowerRank: $sLow1080) at 1080p despite maximal tiebreakers",
                sHigh1080 > sLow1080
            )
        }
    }

    // =========================================================================
    // 3. PRIORITY STREAM DISPATCHER CONCURRENCY & RACE CONDITIONS
    // =========================================================================

    @Test
    fun testPriorityStreamDispatcherReversedArrivalStrictlySorted() = runBlocking {
        // Feed streams in completely reverse order: lowest rank first
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = mutableSetOf(100, 95, 92, 90, 88, 80, 70)

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 150L,
            topSourceGraceMs = 300L,
            top720GraceMs = 1500L,
            activeTopRanks = setOf(100, 95, 92, 90, 88, 80, 70),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        val videasy720 = createLink("VidEasy", "VidEasy [720p]", "https://player.videasy.to/720.m3u8", Qualities.P720.value)
        val peachify720 = createLink("Peachify", "Peachify [720p]", "https://peachify.com/720.m3u8", Qualities.P720.value)
        val cinejoy720 = createLink("CineJoy", "CineJoy [720p]", "https://cinejoy.to/720.m3u8", Qualities.P720.value)
        val rivestream720 = createLink("RiveStream", "RiveStream [720p]", "https://rivestream.live/720.m3u8", Qualities.P720.value)
        val vidup720 = createLink("Vidup", "Vidup [720p]", "https://vidup.to/720.m3u8", Qualities.P720.value)
        val vidcore720 = createLink("Vidcore", "Vidcore [720p]", "https://vidcore.io/720.m3u8", Qualities.P720.value)
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)

        // Arrive in reverse order
        dispatcher.onLinkAccepted(videasy720)
        dispatcher.onLinkAccepted(peachify720)
        dispatcher.onLinkAccepted(cinejoy720)
        dispatcher.onLinkAccepted(rivestream720)
        dispatcher.onLinkAccepted(vidup720)
        dispatcher.onLinkAccepted(vidcore720)

        // All should be buffered in pendingTop720Links waiting for VidLink (100) which is still in flight (within topSourceGraceMs 300ms)
        delay(50L)
        assertTrue("Lower 720p streams must not emit while rank 100 is in-flight within grace window", emitted.isEmpty())

        // Now VidLink 720p arrives at t=60ms
        dispatcher.onLinkAccepted(vidlink720)
        inFlightRanks.remove(100)
        dispatcher.markRankCompleted(100)

        // Mark remaining completed
        inFlightRanks.clear()
        dispatcher.markRankCompleted(95)
        dispatcher.markRankCompleted(92)
        dispatcher.markRankCompleted(90)
        dispatcher.markRankCompleted(88)
        dispatcher.markRankCompleted(80)
        dispatcher.markRankCompleted(70)

        assertEquals("All 7 streams must be emitted", 7, emitted.size)
        assertEquals("1st must be VidLink 720p", vidlink720, emitted[0])
        assertEquals("2nd must be Vidcore 720p", vidcore720, emitted[1])
        assertEquals("3rd must be Vidup 720p", vidup720, emitted[2])
        assertEquals("4th must be RiveStream 720p", rivestream720, emitted[3])
        assertEquals("5th must be CineJoy 720p", cinejoy720, emitted[4])
        assertEquals("6th must be Peachify 720p", peachify720, emitted[5])
        assertEquals("7th must be VidEasy 720p", videasy720, emitted[6])
    }

    @Test
    fun testHigherRanking720pArrivesDuring1080pGraceWindowPreempts1080p() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = mutableSetOf(90, 80, 70)

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 150L,
            topSourceGraceMs = 150L,
            top720GraceMs = 1000L,
            activeTopRanks = setOf(90, 80, 70),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        // VidFast (rank 70) emits 1080p at t=0ms, while higher ranks (YFlix 90, CineJoy 80) are in flight
        val vidfast1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/1080.m3u8", Qualities.P1080.value)
        val cinejoy720 = createLink("CineJoy", "CineJoy [720p]", "https://cinejoy.to/720.m3u8", Qualities.P720.value)

        dispatcher.onLinkAccepted(vidfast1080)
        inFlightRanks.remove(70)
        dispatcher.markRankCompleted(70)

        delay(50L)
        // VidFast 1080p must be held because higher rank 720p candidates (ranks 90, 80) are in flight
        assertTrue("VidFast 1080p must be held waiting for higher-ranked 720p candidates", emitted.isEmpty())

        // CineJoy 720p (rank 80) arrives at t=60ms
        dispatcher.onLinkAccepted(cinejoy720)
        inFlightRanks.remove(80)
        dispatcher.markRankCompleted(80)

        // YFlix (rank 90) finishes with no links
        inFlightRanks.remove(90)
        dispatcher.markRankCompleted(90)

        assertEquals("Both streams must be emitted", 2, emitted.size)
        assertEquals("CineJoy 720p MUST be emitted BEFORE VidFast 1080p", cinejoy720, emitted[0])
        assertEquals("VidFast 1080p must be emitted AFTER higher-ranked 720p", vidfast1080, emitted[1])
    }

    @Test
    fun testGracePeriodExpiryDispatches1080pWhenNo720pArrives() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = mutableSetOf(80, 70)

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 40L,
            subtitleGraceMs = 30L,
            topSourceGraceMs = 30L,
            top720GraceMs = 150L,
            activeTopRanks = setOf(80, 70),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        val vidfast1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/1080.m3u8", Qualities.P1080.value)
        dispatcher.onLinkAccepted(vidfast1080)

        // Held because rank 80 is in flight
        assertEquals(0, emitted.size)

        // Wait past grace window (150ms + margin)
        delay(250L)

        assertEquals("After grace timeout, VidFast 1080p must be released", 1, emitted.size)
        assertEquals(vidfast1080, emitted[0])
    }

    @Test
    fun testFlushReleasesAllHeldStreamsSafelyInOrder() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = mutableSetOf(100, 90, 88, 70)

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 1000L,
            subtitleGraceMs = 1000L,
            topSourceGraceMs = 1000L,
            top720GraceMs = 5000L,
            activeTopRanks = setOf(100, 90, 88, 70),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val rivestream720 = createLink("RiveStream", "RiveStream [720p]", "https://rivestream.live/720.m3u8", Qualities.P720.value)
        val cinejoy4k = createLink("CineJoy", "CineJoy [4K]", "https://cinejoy.to/4k.m3u8", Qualities.P2160.value)
        val videasy480 = createLink("VidEasy", "VidEasy [480p]", "https://player.videasy.to/480.m3u8", Qualities.P480.value)

        // Add various streams while everything is held
        dispatcher.onLinkAccepted(cinejoy4k)
        dispatcher.onLinkAccepted(videasy480)
        dispatcher.onLinkAccepted(vidlink1080)
        dispatcher.onLinkAccepted(rivestream720)

        // Call flush() immediately
        dispatcher.flush()

        assertEquals("All 4 streams must be emitted on flush()", 4, emitted.size)
        assertEquals("Priority #1 on flush must be RiveStream 720p", rivestream720, emitted[0])
        assertEquals("Priority #2 on flush must be VidLink 1080p", vidlink1080, emitted[1])
        assertEquals("Priority #3 on flush must be VidEasy 480p", videasy480, emitted[2])
        assertEquals("Priority #4 on flush must be CineJoy 4K", cinejoy4k, emitted[3])

        // Calling flush() again must be safe and idempotent
        dispatcher.flush()
        assertEquals("No duplicate links on subsequent flush", 4, emitted.size)
    }

    @Test
    fun testPriorityStreamDispatcherHighConcurrencyThreadSafety() = runBlocking {
        val emitted = ConcurrentLinkedQueue<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 20L,
            subtitleGraceMs = 20L,
            topSourceGraceMs = 20L,
            top720GraceMs = 20L
        )

        val totalTasks = 50
        val counter = AtomicInteger(0)

        // Launch 50 concurrent coroutines pushing distinct links simultaneously
        val jobs = (1..totalTasks).map { i ->
            launch(Dispatchers.Default) {
                val quality = if (i % 2 == 0) Qualities.P720.value else Qualities.P1080.value
                val link = createLink("VidLink", "VidLink #$i [${quality}p]", "https://vidlink.pro/stream$i.m3u8", quality)
                dispatcher.onLinkAccepted(link)
                counter.incrementAndGet()
            }
        }
        jobs.joinAll()
        assertEquals(totalTasks, counter.get())

        // Subtitles received concurrently
        dispatcher.onSubtitleReceived()
        delay(100L)
        dispatcher.flush()

        assertEquals("All 50 concurrent streams must be accounted for", totalTasks, emitted.size)
        // Deduplicate check
        val distinctUrls = emitted.map { it.url }.toSet()
        assertEquals("All 50 streams must be distinct without dropped or duplicated items", totalTasks, distinctUrls.size)
    }
}
