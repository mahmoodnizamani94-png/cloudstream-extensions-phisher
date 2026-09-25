package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dedicated Test Suite for Milestone 1: Strict Quality Hierarchy & Deduplication Alignment.
 *
 * Explicitly validates:
 * 1. Acceptance Criterion 1: STREAM_PRIORITY_COMPARATOR strictly orders streams:
 *    720p > 1080p > 480p > 360p/2160p.
 * 2. Acceptance Criterion 2: StreamLinkOptimizer.isBetterThan(streamA, streamB) returns true
 *    when streamA is 720p and streamB is 1080p (given equal or equivalent source tier),
 *    and properly drives StreamDeduplicator upgrades.
 * 3. Acceptance Criterion 3: StreamLinkOptimizer.getQualityPriorityScore(quality) assigns
 *    higher scores to 720p than 1080p, 1080p higher than 480p, and 480p higher than 4K or 360p.
 * 4. Acceptance Criterion 4: Composite scoring ensures top-tier 720p streams always dispatch
 *    ahead of top-tier 1080p streams across all 36 provider combinations.
 * 5. Boundary Tests: 718p widescreen vs 1080p, 800p vs 480p, 576p vs 360p, 4K vs 360p,
 *    and tiebreaker separation under max badges.
 */
class Milestone1QualityHierarchyTest {

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

    @Suppress("DEPRECATION", "unused")
    private fun createSubtitle(lang: String = "English", url: String = "https://example.com/sub.srt"): SubtitleFile {
        return SubtitleFile(
            lang = lang,
            url = url
        )
    }

    // =========================================================================
    // ACCEPTANCE CRITERION 1: STREAM_PRIORITY_COMPARATOR Strict Quality Ordering
    // =========================================================================

    @Test
    fun testAc1_StreamPriorityComparator_StrictQualityOrdering_720_1080_480_360_2160() {
        // Build 5 streams with exact quality values for the same provider
        val stream720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val stream1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val stream480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)
        val stream360 = createLink("VidLink", "VidLink [360p]", "https://vidlink.pro/360.m3u8", Qualities.P360.value)
        val stream2160 = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/2160.m3u8", Qualities.P2160.value)

        // Comparator symmetry and anti-reflexivity checks
        assertTrue("720p must precede 1080p", StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(stream720, stream1080) < 0)
        assertTrue("1080p must follow 720p", StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(stream1080, stream720) > 0)
        assertTrue("1080p must precede 480p", StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(stream1080, stream480) < 0)
        assertTrue("480p must precede 360p", StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(stream480, stream360) < 0)
        assertTrue("360p must precede 2160p/4K", StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(stream360, stream2160) < 0)
        assertEquals("Identical stream comparison must be 0", 0, StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(stream720, stream720))

        // Sorting arbitrary shuffled permutations must deterministically produce [720, 1080, 480, 360, 2160]
        val shuffled = listOf(stream2160, stream360, stream1080, stream720, stream480)
        val sorted = shuffled.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)

        assertEquals("Index 0 must be 720p", stream720, sorted[0])
        assertEquals("Index 1 must be 1080p", stream1080, sorted[1])
        assertEquals("Index 2 must be 480p", stream480, sorted[2])
        assertEquals("Index 3 must be 360p", stream360, sorted[3])
        assertEquals("Index 4 must be 2160p", stream2160, sorted[4])
    }

    @Test
    fun testAc1_StreamPriorityComparator_CrossSourceQualityTransitivity() {
        // Lowest top-tier source (VidEasy, rank 70) vs highest top-tier source (VidLink, rank 100)
        val videasy720 = createLink("VidEasy", "VidEasy [720p]", "https://videasy.net/720.m3u8", Qualities.P720.value)
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val videasy1080 = createLink("VidEasy", "VidEasy [1080p]", "https://videasy.net/1080.m3u8", Qualities.P1080.value)
        val vidlink480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)
        val videasy480 = createLink("VidEasy", "VidEasy [480p]", "https://videasy.net/480.m3u8", Qualities.P480.value)
        val vidlink360 = createLink("VidLink", "VidLink [360p]", "https://vidlink.pro/360.m3u8", Qualities.P360.value)
        val videasy360 = createLink("VidEasy", "VidEasy [360p]", "https://videasy.net/360.m3u8", Qualities.P360.value)
        val vidlink2160 = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/2160.m3u8", Qualities.P2160.value)

        val list = listOf(vidlink2160, vidlink360, vidlink480, vidlink1080, videasy360, videasy480, videasy1080, videasy720)
        val sorted = list.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)

        // 720p group must come before 1080p group, even if 720p is from VidEasy and 1080p is from VidLink
        assertTrue("VidEasy 720p must precede VidLink 1080p in sorted order", sorted.indexOf(videasy720) < sorted.indexOf(vidlink1080))
        assertTrue("VidEasy 1080p must precede VidLink 480p in sorted order", sorted.indexOf(videasy1080) < sorted.indexOf(vidlink480))
        assertTrue("VidEasy 480p must precede VidLink 360p in sorted order", sorted.indexOf(videasy480) < sorted.indexOf(vidlink360))
        assertTrue("VidEasy 360p must precede VidLink 2160p in sorted order", sorted.indexOf(videasy360) < sorted.indexOf(vidlink2160))
    }

    // =========================================================================
    // ACCEPTANCE CRITERION 2: isBetterThan Alignment & Deduplication Integration
    // =========================================================================

    @Test
    fun testAc2_IsBetterThan_StrictQualityHierarchy_720Beats1080() {
        val link720 = createLink("VidLink", "VidLink [720p]", "https://cdn.example.com/stream.m3u8", Qualities.P720.value)
        val link1080 = createLink("VidLink", "VidLink [1080p]", "https://cdn.example.com/stream.m3u8", Qualities.P1080.value)
        val link480 = createLink("VidLink", "VidLink [480p]", "https://cdn.example.com/stream.m3u8", Qualities.P480.value)
        val link360 = createLink("VidLink", "VidLink [360p]", "https://cdn.example.com/stream.m3u8", Qualities.P360.value)
        val link2160 = createLink("VidLink", "VidLink [4K]", "https://cdn.example.com/stream.m3u8", Qualities.P2160.value)
        val linkUnknown = createLink("VidLink", "VidLink [Unknown]", "https://cdn.example.com/stream.m3u8", Qualities.Unknown.value)

        // 720p beats 1080p strictly (Core Milestone 1 contract)
        assertTrue("720p must be better than 1080p given equal source tier", StreamLinkOptimizer.isBetterThan(link720, link1080))
        assertFalse("1080p must NOT be better than 720p given equal source tier", StreamLinkOptimizer.isBetterThan(link1080, link720))

        // Full monotonic chain in isBetterThan
        assertTrue("1080p must be better than 480p", StreamLinkOptimizer.isBetterThan(link1080, link480))
        assertFalse("480p must NOT be better than 1080p", StreamLinkOptimizer.isBetterThan(link480, link1080))

        assertTrue("480p must be better than 360p", StreamLinkOptimizer.isBetterThan(link480, link360))
        assertFalse("360p must NOT be better than 480p", StreamLinkOptimizer.isBetterThan(link360, link480))

        assertTrue("360p must be better than 2160p (playable SD over stuttering 4K)", StreamLinkOptimizer.isBetterThan(link360, link2160))
        assertFalse("2160p must NOT be better than 360p", StreamLinkOptimizer.isBetterThan(link2160, link360))

        assertTrue("2160p must be better than Unknown", StreamLinkOptimizer.isBetterThan(link2160, linkUnknown))
        assertFalse("Unknown must NOT be better than 2160p", StreamLinkOptimizer.isBetterThan(linkUnknown, link2160))
    }

    @Test
    fun testAc2_IsBetterThan_DeduplicatorIntegration_720pReplaces1080p() {
        val emittedList = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        val cdn1080 = createLink("VidLink", "Stream [1080p]", "https://storage.cdn.com/stream.mp4", Qualities.P1080.value)
        val cdn720 = createLink("VidLink", "Stream [720p]", "https://storage.cdn.com/stream.mp4", Qualities.P720.value)

        // Scenario A: 1080p arrives first, then 720p arrives for identical canonical URL
        assertEquals("Initial 1080p must be accepted as NEW", StreamLinkOptimizer.DeduplicationResult.NEW, deduplicator.emitDetailed(cdn1080))
        assertEquals(1, deduplicator.getEmittedCount())
        assertEquals(Qualities.P1080.value, deduplicator.getEmittedLinks().first().quality)

        // 720p arrives -> MUST UPGRADE 1080p!
        assertEquals("720p must UPGRADE existing 1080p entry", StreamLinkOptimizer.DeduplicationResult.UPGRADED, deduplicator.emitDetailed(cdn720))
        assertEquals(1, deduplicator.getEmittedCount())
        assertEquals("Emitted quality must now be 720p", Qualities.P720.value, deduplicator.getEmittedLinks().first().quality)

        // Scenario B: Subsequent 1080p arrives after 720p is active -> MUST BE DROPPED!
        assertEquals("Subsequent 1080p must be DROPPED against active 720p", StreamLinkOptimizer.DeduplicationResult.DROPPED, deduplicator.emitDetailed(cdn1080))
        assertEquals(1, deduplicator.getEmittedCount())
        assertEquals(Qualities.P720.value, deduplicator.getEmittedLinks().first().quality)
    }

    // =========================================================================
    // ACCEPTANCE CRITERION 3: getQualityPriorityScore Function Verification
    // =========================================================================

    @Test
    fun testAc3_GetQualityPriorityScore_StrictValuesAndMonotonicity() {
        val score720 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P720.value)
        val score1080 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1080.value)
        val score480 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P480.value)
        val score576 = StreamLinkOptimizer.getQualityPriorityScore(576)
        val score360 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P360.value)
        val score240 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P240.value)
        val score1440 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1440.value)
        val score2160 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P2160.value)
        val score4320 = StreamLinkOptimizer.getQualityPriorityScore(4320)
        val scoreUnknown = StreamLinkOptimizer.getQualityPriorityScore(Qualities.Unknown.value)
        val scoreInvalid = StreamLinkOptimizer.getQualityPriorityScore(-1)

        // Explicit score value verification
        assertEquals(10000, score720)
        assertEquals(9000, score1080)
        assertEquals(7000, score480)
        assertEquals(6500, score576)
        assertEquals(5360, score360)
        assertEquals(5240, score240)
        assertEquals(3640, score1440)
        assertEquals(2920, score2160)
        assertEquals(1000, score4320)
        assertEquals(0, scoreUnknown)
        assertEquals(0, scoreInvalid)

        // Strict inequalities: 720p > 1080p > 480p > (4K or 360p)
        assertTrue("720p (10000) > 1080p (9000)", score720 > score1080)
        assertTrue("1080p (9000) > 480p (7000)", score1080 > score480)
        assertTrue("480p (7000) > 576p (6500)", score480 > score576)
        assertTrue("576p (6500) > 360p (5360)", score576 > score360)
        assertTrue("360p (5360) > 240p (5240)", score360 > score240)
        assertTrue("240p (5240) > 1440p (3640)", score240 > score1440)
        assertTrue("1440p (3640) > 2160p (2920)", score1440 > score2160)
        assertTrue("2160p (2920) > 4320p (1000)", score2160 > score4320)
        assertTrue("4320p (1000) > Unknown (0)", score4320 > scoreUnknown)

        // AC3 explicit comparison: 480p higher than 4K or 360p
        assertTrue("480p must score higher than 4K/2160p", score480 > score2160)
        assertTrue("480p must score higher than 360p", score480 > score360)
    }

    @Test
    fun testAc3_GetQualityPriorityScore_RangesAndPrioritizedFlags() {
        // HD range 700..749 must all evaluate to 10000
        for (q in 700..749) {
            assertEquals("Quality $q must yield 10000", 10000, StreamLinkOptimizer.getQualityPriorityScore(q))
        }

        // isQualityPrioritized returns true for top user tiers (700..1088)
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(720))
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(1080))
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(718))
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(800))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(480))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(360))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(2160))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(Qualities.Unknown.value))
    }

    // =========================================================================
    // ACCEPTANCE CRITERION 4: Composite Scoring & Top-Tier 720p Dispatch Priority
    // =========================================================================

    @Test
    fun testAc4_CompositeScoring_All36TopTierCombinations_720pAlwaysBeats1080p() {
        val topProviders = listOf("Vidlink", "Vidcore", "Vidup", "RiveStream", "CineJoy", "VidEasy")

        for (p720 in topProviders) {
            // Unadorned 720p link (0 bitrate, no badges)
            val link720 = createLink(
                source = p720,
                name = "$p720 [720p]",
                url = "https://$p720.com/720.m3u8",
                quality = Qualities.P720.value
            )
            val score720 = StreamLinkOptimizer.getStreamCompositeScore(link720)

            for (p1080 in topProviders) {
                // Heavily badged 1080p link (max bitrate, REMUX, DV, TrueHD, 7.1, complete headers)
                val link1080 = createLink(
                    source = p1080,
                    name = "$p1080 [1080p] [50000kbps] [REMUX] [DV] [HDR10+] [HEVC] [TrueHD] [7.1]",
                    url = "https://$p1080.com/1080.m3u8",
                    quality = Qualities.P1080.value,
                    headers = mapOf(
                        StreamLinkOptimizer.HEADER_USER_AGENT to StreamLinkOptimizer.MODERN_DESKTOP_UA,
                        StreamLinkOptimizer.HEADER_ACCEPT to "*/*",
                        StreamLinkOptimizer.HEADER_ACCEPT_ENCODING to "identity",
                        StreamLinkOptimizer.HEADER_CONNECTION to "keep-alive",
                        StreamLinkOptimizer.HEADER_SEC_FETCH_DEST to "video"
                    )
                )
                val score1080 = StreamLinkOptimizer.getStreamCompositeScore(link1080)

                assertTrue(
                    "Any top-tier 720p ($p720: $score720) must strictly beat any top-tier 1080p ($p1080: $score1080)",
                    score720 > score1080
                )
                assertTrue(
                    "Separation between $p720 720p and $p1080 1080p must exceed 1,540 points",
                    (score720 - score1080) >= 1540f
                )
            }
        }
    }

    @Test
    fun testAc4_PriorityStreamDispatcher_StagingHolds1080pUntil720pDispatched() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            fhdGraceMs = 150L,
            sdGraceMs = 200L
        )

        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val vidsrc720 = createLink("VidSrc", "VidSrc [720p]", "https://vidsrc.xyz/720.m3u8", Qualities.P720.value)

        // 1080p arrives first at t = 0
        dispatcher.onLinkAccepted(vidlink1080)
        // 720p arrives from lowest top-tier provider at t = 20ms during FHD grace period
        kotlinx.coroutines.delay(20)
        dispatcher.onLinkAccepted(vidsrc720)

        // Wait for staging windows to settle
        kotlinx.coroutines.delay(250)
        dispatcher.flush()

        assertEquals("Both links must eventually be emitted", 2, emittedLinks.size)
        assertEquals("720p MUST be emitted first despite arriving second from a lower-ranked provider", vidsrc720, emittedLinks[0])
        assertEquals("1080p MUST follow 720p", vidlink1080, emittedLinks[1])
    }

    // =========================================================================
    // BOUNDARY TESTS
    // =========================================================================

    @Test
    fun testBoundary_718pWidescreenVs1080p() {
        // 1280x718 (2.39:1 anamorphic 720p) vs 1080p
        val link718 = createLink("VidLink", "VidLink 1280x718 [718p]", "https://vidlink.pro/718.m3u8", 718)
        val link1080 = createLink("VidLink", "VidLink 1920x1080 [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)

        assertEquals("718p must yield 720p quality tier score 10000", 10000, StreamLinkOptimizer.getQualityPriorityScore(718))
        assertEquals("1080p yields 9000", 9000, StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1080.value))

        assertTrue("718p widescreen must be better than 1080p", StreamLinkOptimizer.isBetterThan(link718, link1080))
        assertFalse("1080p must NOT be better than 718p widescreen", StreamLinkOptimizer.isBetterThan(link1080, link718))

        val sorted = listOf(link1080, link718).sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals("718p must be sorted ahead of 1080p", link718, sorted[0])
    }

    @Test
    fun testBoundary_800pWidescreenVs480p() {
        // 1920x800 (2.39:1 anamorphic 1080p) vs 480p
        val link800 = createLink("VidLink", "VidLink 1920x800 [800p]", "https://vidlink.pro/800.m3u8", 800)
        val link480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)

        val score800 = StreamLinkOptimizer.getQualityPriorityScore(800)
        val score480 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P480.value)

        assertEquals("800p score is 8500 + minOf(499, 800 - 750) = 8550", 8550, score800)
        assertEquals("480p score is 7000", 7000, score480)
        assertTrue("800p FHD widescreen must outscore 480p SD", score800 > score480)

        assertTrue("800p widescreen must be better than 480p", StreamLinkOptimizer.isBetterThan(link800, link480))
        assertFalse("480p must NOT be better than 800p widescreen", StreamLinkOptimizer.isBetterThan(link480, link800))

        val sorted = listOf(link480, link800).sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals("800p widescreen must be sorted ahead of 480p", link800, sorted[0])
    }

    @Test
    fun testBoundary_576pPalDvdVs360p() {
        // 720x576 (PAL DVD Standard Definition) vs 640x360
        val link576 = createLink("VidLink", "VidLink [576p]", "https://vidlink.pro/576.m3u8", 576)
        val link360 = createLink("VidLink", "VidLink [360p]", "https://vidlink.pro/360.m3u8", Qualities.P360.value)

        val score576 = StreamLinkOptimizer.getQualityPriorityScore(576)
        val score360 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P360.value)

        assertEquals("576p PAL score is 6500", 6500, score576)
        assertEquals("360p score is 5360", 5360, score360)
        assertTrue("576p must score higher than 360p", score576 > score360)

        assertTrue("576p must be better than 360p", StreamLinkOptimizer.isBetterThan(link576, link360))
        assertFalse("360p must NOT be better than 576p", StreamLinkOptimizer.isBetterThan(link360, link576))

        val sorted = listOf(link360, link576).sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals("576p must be sorted ahead of 360p", link576, sorted[0])
    }

    @Test
    fun testBoundary_4kVs360p_PlayabilityPrioritization() {
        val link360 = createLink("VidLink", "VidLink [360p]", "https://vidlink.pro/360.m3u8", Qualities.P360.value)
        val link4k = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/2160.m3u8", Qualities.P2160.value)

        val score360 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P360.value)
        val score4k = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P2160.value)

        assertEquals("360p quality priority score is 5360", 5360, score360)
        assertEquals("4K quality priority score is 2920", 2920, score4k)
        assertTrue("360p must score higher than 4K for low-end device playability", score360 > score4k)

        assertTrue("360p must be better than 4K at equal provider rank", StreamLinkOptimizer.isBetterThan(link360, link4k))
        assertFalse("4K must NOT be better than 360p at equal provider rank", StreamLinkOptimizer.isBetterThan(link4k, link360))

        val sorted = listOf(link4k, link360).sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals("360p must be sorted ahead of 4K", link360, sorted[0])

        // Top-tier 4K still preserves dominance over secondary 360p (source hierarchy integrity)
        val secondary360 = createLink("MovieBox", "MovieBox [360p]", "https://moviebox.com/360.m3u8", Qualities.P360.value)
        val compTop4k = StreamLinkOptimizer.getStreamCompositeScore(link4k)
        val compSec360 = StreamLinkOptimizer.getStreamCompositeScore(secondary360)
        assertTrue("Top-tier 4K ($compTop4k) must still beat secondary 360p ($compSec360)", compTop4k > compSec360)
    }

    @Test
    fun testBoundary_TiebreakerSeparationUnderMaxBadges() {
        val plain1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val maxBadged1080 = createLink(
            source = "VidLink",
            name = "VidLink [1080p] [100000kbps] [REMUX] [DV] [HDR10+] [HEVC] [AV1] [10-bit] [TrueHD] [DTS-HD] [Atmos] [7.1] [Dual Audio] [Multi Audio]",
            url = "https://vidlink.pro/1080.m3u8",
            quality = Qualities.P1080.value,
            headers = mapOf(
                StreamLinkOptimizer.HEADER_USER_AGENT to StreamLinkOptimizer.MODERN_DESKTOP_UA,
                StreamLinkOptimizer.HEADER_ACCEPT to "*/*",
                StreamLinkOptimizer.HEADER_ACCEPT_ENCODING to "identity",
                StreamLinkOptimizer.HEADER_CONNECTION to "keep-alive",
                StreamLinkOptimizer.HEADER_SEC_FETCH_DEST to "video"
            )
        )

        val scorePlain = StreamLinkOptimizer.getStreamCompositeScore(plain1080)
        val scoreMax = StreamLinkOptimizer.getStreamCompositeScore(maxBadged1080)

        // Tiebreaker difference must exactly equal 9.9f ceiling
        val diff = scoreMax - scorePlain
        assertEquals("Tiebreaker boost must be strictly capped at 9.9f", 9.9f, diff, 0.01f)

        // Ensure provider rank cannot be inverted by tiebreaker badges
        val cinejoyPlain720 = createLink("CineJoy", "CineJoy [720p]", "https://solarpanelcleaning.cc/720.m3u8", Qualities.P720.value)
        val videasyMax720 = createLink(
            source = "VidEasy",
            name = "VidEasy [720p] [50000kbps] [REMUX] [DV] [Atmos]",
            url = "https://videasy.net/720.m3u8",
            quality = Qualities.P720.value,
            headers = mapOf(StreamLinkOptimizer.HEADER_ACCEPT_ENCODING to "identity")
        )

        val scoreCineJoy = StreamLinkOptimizer.getStreamCompositeScore(cinejoyPlain720)
        val scoreVidEasy = StreamLinkOptimizer.getStreamCompositeScore(videasyMax720)

        assertTrue(
            "Higher ranked CineJoy (score $scoreCineJoy) must beat lower ranked VidEasy with max badges (score $scoreVidEasy)",
            scoreCineJoy > scoreVidEasy
        )
        assertTrue("Margin must be at least 90.0 points", (scoreCineJoy - scoreVidEasy) >= 90.0f)
    }
}
