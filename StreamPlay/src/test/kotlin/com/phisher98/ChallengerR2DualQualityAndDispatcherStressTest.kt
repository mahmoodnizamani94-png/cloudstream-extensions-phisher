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
        // Minimal possible 720p stream: rank-0 secondary source (MovieBox), 0 bitrate, 0 badges, 0 headers
        val minMovieBox720 = createLink(
            source = "MovieBox",
            name = "MovieBox [720p]",
            url = "https://moviebox.com/stream_minimal.m3u8",
            quality = Qualities.P720.value
        )
        val minScore720 = StreamLinkOptimizer.getStreamCompositeScore(minMovieBox720)
        assertEquals("MovieBox 720p score must be exactly 800.0f (secondary 720p base)", 800.0f, minScore720, 0.001f)

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
        // rank 100 x 10,000 + 8,000 (1080p) + 9.9 tiebreaker (delta widened for the ~1e6 float grid)
        assertEquals("Maximal top-tier 1080p score must be exactly 1,008,009.9f", 1_008_009.9f, maxScore1080, 0.1f)

        // SOURCE RANK IS PRIMARY: the top-tier 1080p always outranks the secondary 720p
        assertTrue(StreamLinkOptimizer.isStreamBetter(maxVidLink1080, minMovieBox720))
        assertFalse(StreamLinkOptimizer.isStreamBetter(minMovieBox720, maxVidLink1080))

        // Permute all providers across 720p vs 1080p with randomized or edge-case badge sets.
        // Ranks: VidLink 100 > AnimePahe 90 > every other (decommissioned) label at 0.
        val providers = listOf(
            "VidLink" to 100,
            "AnimePahe" to 90,
            "RiveStream" to 0,
            "VidNest" to 0,
            "Vidup" to 0,
            "CineJoy" to 0,
            "HexaSU" to 0,
            "AutoEmbed" to 0,
            "MovieBox" to 0,
            "YFlix" to 0
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
            val rank720 = StreamLinkOptimizer.getSourcePriorityRank(link720)

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
                            val rank1080 = StreamLinkOptimizer.getSourcePriorityRank(link1080)
                            if (rank720 >= rank1080) {
                                // Same source rank (or a higher-ranked 720p): quality decides, 720p wins
                                assertTrue(
                                    "720p ($p720Name: $score720) must strictly beat same-rank 1080p ($p1080Name: $score1080)",
                                    score720 > score1080
                                )
                                assertTrue(StreamLinkOptimizer.isStreamBetter(link720, link1080))
                            } else {
                                // Cross-source: the higher-ranked 1080p always wins
                                assertTrue(
                                    "Higher-ranked 1080p ($p1080Name: $score1080) must beat 720p ($p720Name: $score720)",
                                    score1080 > score720
                                )
                                assertTrue(StreamLinkOptimizer.isStreamBetter(link1080, link720))
                            }
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
        // The v18 registry has three ranked sources; every other label scores rank 0, so
        // monotonicity is asserted across the distinct ranks: VidLink 100 > VidEm 95 > AnimePahe 90 > secondary 0.
        val providersInOrder = listOf(
            "VidLink" to 100,
            "VidEm" to 95,
            "AnimePahe" to 90
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
        // Feed streams in completely reverse rank order: rank 0 first, then 90, then 100
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = mutableSetOf(100, 90)

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 150L,
            topSourceGraceMs = 300L,
            top720GraceMs = 1500L,
            activeTopRanks = setOf(100, 90),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        val moviebox720 = createLink("MovieBox", "MovieBox [720p]", "https://moviebox.com/720.m3u8", Qualities.P720.value)
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://player.autoembed.cc/720.m3u8", Qualities.P720.value)
        val hexasu720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)
        val cinejoy720 = createLink("CineJoy", "CineJoy [720p]", "https://cinejoy.to/720.m3u8", Qualities.P720.value)
        val vidup720 = createLink("Vidup", "Vidup [720p]", "https://vidup.to/720.m3u8", Qualities.P720.value)
        val animepahe720 = createLink("AnimePahe", "AnimePahe [720p]", "https://animepahe.pw/720.m3u8", Qualities.P720.value)
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)

        // Arrive in reverse order: all rank-0 secondaries, then rank 90, rank 100 last
        dispatcher.onLinkAccepted(moviebox720)
        dispatcher.onLinkAccepted(autoembed720)
        dispatcher.onLinkAccepted(hexasu720)
        dispatcher.onLinkAccepted(cinejoy720)
        dispatcher.onLinkAccepted(vidup720)
        dispatcher.onLinkAccepted(animepahe720)

        // All are buffered in pendingTop720Links waiting for VidLink (100) which is still in flight (within topSourceGraceMs 300ms)
        delay(50L)
        assertTrue("Lower 720p streams must not emit while rank 100 is in-flight within grace window", emitted.isEmpty())

        // Now VidLink 720p arrives at t=60ms
        dispatcher.onLinkAccepted(vidlink720)
        inFlightRanks.remove(100)
        dispatcher.markRankCompleted(100)

        // Mark remaining completed
        inFlightRanks.clear()
        dispatcher.markRankCompleted(90)

        // VidLink (100) and AnimePahe (90) outrank everything; the five rank-0 secondaries tie at
        // 800.0f, so the stable comparator drains them in arrival order.
        assertEquals("All 7 streams must be emitted", 7, emitted.size)
        assertEquals("1st must be VidLink 720p", vidlink720, emitted[0])
        assertEquals("2nd must be AnimePahe 720p", animepahe720, emitted[1])
        assertEquals("3rd must be MovieBox 720p", moviebox720, emitted[2])
        assertEquals("4th must be AutoEmbed 720p", autoembed720, emitted[3])
        assertEquals("5th must be HexaSU 720p", hexasu720, emitted[4])
        assertEquals("6th must be CineJoy 720p", cinejoy720, emitted[5])
        assertEquals("7th must be Vidup 720p", vidup720, emitted[6])
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
        val inFlightRanks = mutableSetOf(88, 85)

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 40L,
            subtitleGraceMs = 30L,
            topSourceGraceMs = 30L,
            top720GraceMs = 150L,
            activeTopRanks = setOf(88, 85),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        val autoembed1080 = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/1080.m3u8", Qualities.P1080.value)
        dispatcher.onLinkAccepted(autoembed1080)

        // Held because rank 88 is in flight
        assertEquals(0, emitted.size)

        // Wait past grace window (150ms + margin)
        delay(250L)

        assertEquals("After grace timeout, AutoEmbed 1080p must be released", 1, emitted.size)
        assertEquals(autoembed1080, emitted[0])
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
            activeTopRanks = setOf(100, 90, 88, 80),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val hexasu720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)
        val cinejoy4k = createLink("CineJoy", "CineJoy [4K]", "https://cinejoy.to/4k.m3u8", Qualities.P2160.value)
        val moviebox480 = createLink("MovieBox", "MovieBox [480p]", "https://moviebox.com/480.m3u8", Qualities.P480.value)

        // Add various streams while everything is held
        dispatcher.onLinkAccepted(cinejoy4k)
        dispatcher.onLinkAccepted(moviebox480)
        dispatcher.onLinkAccepted(vidlink1080)
        dispatcher.onLinkAccepted(hexasu720)

        // Call flush() immediately
        dispatcher.flush()

        // Source rank dominates: the top-tier VidLink 1080p is priority #1 even though a
        // secondary 720p exists; quality then orders the rank-0 leftovers (720 > 480 > 4K).
        assertEquals("All 4 streams must be emitted on flush()", 4, emitted.size)
        assertEquals("Priority #1 on flush must be VidLink 1080p", vidlink1080, emitted[0])
        assertEquals("Priority #2 on flush must be HexaSU 720p", hexasu720, emitted[1])
        assertEquals("Priority #3 on flush must be MovieBox 480p", moviebox480, emitted[2])
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
