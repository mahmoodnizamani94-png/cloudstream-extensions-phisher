package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DualQualityAndSotaHierarchyTest {

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

    @Suppress("DEPRECATION")
    private fun createSubtitle(lang: String = "English", url: String = "https://example.com/sub.srt"): SubtitleFile {
        return SubtitleFile(
            lang = lang,
            url = url
        )
    }

    @Test
    fun testQualityPriorityScoreHierarchy() {
        // Users must get 720 as #1 priority, then 1080, then 480, then intermediate SD, then above 1080 (1440/4k), then <480
        val score720 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P720.value)
        val score1080 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1080.value)
        val score480 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P480.value)
        val score576 = StreamLinkOptimizer.getQualityPriorityScore(576)
        val score1440 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1440.value)
        val score2160 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P2160.value)
        val score360 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P360.value)
        val scoreUnknown = StreamLinkOptimizer.getQualityPriorityScore(Qualities.Unknown.value)

        assertEquals(10000, score720)
        assertEquals(9000, score1080)
        assertEquals(7000, score480)
        assertEquals(6500, score576)
        assertEquals(5360, score360)
        assertEquals(3640, score1440)
        assertEquals(2920, score2160)
        assertEquals(0, scoreUnknown)

        // Strict monotonicity checks: 720 > 1080 > 480 > 576 > 360 (> all below 720) > 1440 > 2160 (> all above 1080) > Unknown
        assertTrue("720p must beat 1080p as #1 priority", score720 > score1080)
        assertTrue("1080p must beat 480p", score1080 > score480)
        assertTrue("480p must beat 576p", score480 > score576)
        assertTrue("576p must beat 360p", score576 > score360)
        assertTrue("360p (below 720) must beat 1440p (above 1080)", score360 > score1440)
        assertTrue("1440p must beat 2160p", score1440 > score2160)
        assertTrue("2160p must beat Unknown", score2160 > scoreUnknown)

        // Prioritized checker
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(Qualities.P720.value))
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(Qualities.P1080.value))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(Qualities.P2160.value))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(Qualities.P480.value))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(Qualities.Unknown.value))
    }

    @Test
    fun testSourcePriorityRankStrictMonotonicity() {
        // Priority order: VidLink (100) > YFlix/HexaSU (90) > CineJoy/AutoEmbed (80) > VidFast (70) > VidEasy (60) > VidSrc (55)
        val rVidlink = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidLink", "VidLink", "https://vidlink.pro/v.m3u8"))
        val rYflix = StreamLinkOptimizer.getSourcePriorityRank(createLink("YFlix", "YFlix", "https://yflix.to/v.m3u8"))
        val rMoviesflix = StreamLinkOptimizer.getSourcePriorityRank(createLink("MoviesFlix", "MoviesFlix", "https://moviesflix.to/v.m3u8"))
        val rCinejoy = StreamLinkOptimizer.getSourcePriorityRank(createLink("CineJoy", "CineJoy", "https://cinejoy.to/v.m3u8"))
        val rHexa = StreamLinkOptimizer.getSourcePriorityRank(createLink("HexaSU", "HexaSU", "https://hexa.su/v.m3u8"))
        val rAutoembed = StreamLinkOptimizer.getSourcePriorityRank(createLink("AutoEmbed", "AutoEmbed", "https://autoembed.cc/v.m3u8"))
        val rVidfast = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidFast", "VidFast", "https://vidfast.vc/v.m3u8"))
        val rVideasy = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidEasy", "VidEasy", "https://player.videasy.to/v.m3u8"))
        val rVidsrc = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidSrc", "VidSrc", "https://vidsrc.xyz/v.m3u8"))

        assertEquals(100, rVidlink)
        assertEquals(90, rYflix)
        assertEquals(90, rMoviesflix)
        assertEquals(90, rHexa)
        assertEquals(80, rCinejoy)
        assertEquals(80, rAutoembed)
        assertEquals(70, rVidfast)
        assertEquals(60, rVideasy)
        assertEquals(55, rVidsrc)

        // Monotonicity of modern SOTA providers
        assertTrue(rVidlink > rYflix)
        assertTrue(rYflix > rCinejoy)
        assertTrue(rCinejoy > rVidfast)
        assertTrue(rVidfast > rVideasy)
        assertTrue(rVideasy > rVidsrc)

        // Legacy compatibility preserved
        assertTrue(rVidlink > rHexa)
        assertTrue(rHexa > rAutoembed)
        assertTrue(rAutoembed > rVidfast)
    }

    @Test
    fun testCompositeStreamScoreOrdering() {
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val vidlink480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)
        val vidlink4k = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/4k.m3u8", Qualities.P2160.value)

        val s720 = StreamLinkOptimizer.getStreamCompositeScore(vidlink720)
        val s1080 = StreamLinkOptimizer.getStreamCompositeScore(vidlink1080)
        val s480 = StreamLinkOptimizer.getStreamCompositeScore(vidlink480)
        val s4k = StreamLinkOptimizer.getStreamCompositeScore(vidlink4k)

        assertTrue("VidLink 720p score ($s720) > 1080p ($s1080)", s720 > s1080)
        assertTrue("VidLink 1080p score ($s1080) > 480p ($s480)", s1080 > s480)
        assertTrue("VidLink 480p score ($s480) > 4K ($s4k)", s480 > s4k)

        // Comparator check: 720p first, then 1080p, then 480p, then 4K
        val list = listOf(vidlink4k, vidlink480, vidlink1080, vidlink720)
        val sorted = list.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals(vidlink720, sorted[0])
        assertEquals(vidlink1080, sorted[1])
        assertEquals(vidlink480, sorted[2])
        assertEquals(vidlink4k, sorted[3])
    }

    @Test
    fun testCrossSourceQualityFirstPrioritization() {
        // 720p is #1 priority across sources, then 1080p, then 480p, then 4K
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)
        val hexa1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/1080.m3u8", Qualities.P1080.value)
        val vidlink480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)
        val vidlink4k = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/4k.m3u8", Qualities.P2160.value)

        val sAuto720 = StreamLinkOptimizer.getStreamCompositeScore(autoembed720)
        val sHexa1080 = StreamLinkOptimizer.getStreamCompositeScore(hexa1080)
        val sVidlink480 = StreamLinkOptimizer.getStreamCompositeScore(vidlink480)
        val sVidlink4k = StreamLinkOptimizer.getStreamCompositeScore(vidlink4k)

        assertTrue("AutoEmbed 720p ($sAuto720) must beat HexaSU 1080p ($sHexa1080)", sAuto720 > sHexa1080)
        assertTrue("HexaSU 1080p ($sHexa1080) must beat VidLink 480p ($sVidlink480)", sHexa1080 > sVidlink480)
        assertTrue("VidLink 480p ($sVidlink480) must beat VidLink 4K ($sVidlink4k)", sVidlink480 > sVidlink4k)
    }

    @Test
    fun testEarlySatisfactionControllerDualQualitiesAndSubtitlesGating() {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 2,
            qualityThreshold = Qualities.P720.value,
            requireSubtitles = true,
            requireDualQualities = true
        )
        val controller = EarlySatisfactionController(config)

        // 1. Initially unsatisfied
        assertFalse(controller.isSatisfied())
        assertFalse(controller.has1080p())
        assertFalse(controller.has720p())
        assertFalse(controller.hasBoth720And1080())
        assertFalse(controller.hasSubtitles())

        // 2. Emit 1080p link alone -> unsatisfied
        val link1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/v1080.m3u8", Qualities.P1080.value)
        val satisfiedAfter1080 = controller.onLinkEmitted(link1080)
        assertFalse("Cannot satisfy with only 1080p when dual qualities and subtitles are required", satisfiedAfter1080)
        assertTrue(controller.has1080p())
        assertFalse(controller.has720p())
        assertFalse(controller.hasBoth720And1080())
        assertFalse(controller.hasSubtitles())

        // 3. Emit subtitles alone -> unsatisfied (720p still missing)
        val satisfiedAfterSub = controller.onSubtitleEmitted(createSubtitle("English", "https://vidlink.pro/en.srt"))
        assertFalse("Cannot satisfy without 720p stream", satisfiedAfterSub)
        assertTrue(controller.has1080p())
        assertFalse(controller.has720p())
        assertTrue(controller.hasSubtitles())

        // 4. Emit 720p link -> BOTH 1080p and 720p present + subtitles present -> SATISFIED!
        val link720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/v720.m3u8", Qualities.P720.value)
        val satisfiedAfter720 = controller.onLinkEmitted(link720)
        assertTrue("Must be satisfied when 1080p, 720p, and subtitles are all delivered", satisfiedAfter720)
        assertTrue(controller.isSatisfied())
        assertTrue(controller.has1080p())
        assertTrue(controller.has720p())
        assertTrue(controller.hasBoth720And1080())
        assertTrue(controller.hasSubtitles())
    }

    @Test
    fun testEarlySatisfactionControllerGatingSubtitlesWhenDualQualitiesPresent() {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            requireSubtitles = true,
            requireDualQualities = true
        )
        val controller = EarlySatisfactionController(config)

        val link1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/v1080.m3u8", Qualities.P1080.value)
        val link720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/v720.m3u8", Qualities.P720.value)

        controller.onLinkEmitted(link1080)
        val satisfiedAfterBothQualities = controller.onLinkEmitted(link720)
        assertFalse("Must NOT satisfy until subtitles arrive when requireSubtitles is true", satisfiedAfterBothQualities)
        assertFalse(controller.isSatisfied())

        controller.onSubtitleEmitted(createSubtitle("English", "https://hexa.su/sub.vtt"))
        assertTrue("Must satisfy immediately once subtitles arrive", controller.isSatisfied())
    }

    @Test
    fun testEarlySatisfactionControllerSafetyCeilings() {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            requireSubtitles = true,
            requireDualQualities = true
        )
        val controller = EarlySatisfactionController(config)

        // If 4 links arrive with subtitles even if 720p is absent, ceiling prevents stalling
        repeat(4) { idx ->
            controller.onLinkEmitted(createLink("AutoEmbed", "AutoEmbed Server $idx", "https://autoembed.cc/s$idx.m3u8", Qualities.P1080.value))
        }
        assertFalse("Ceiling of 4 links requires subtitles", controller.isSatisfied())

        controller.onSubtitleEmitted(createSubtitle("English", "https://autoembed.cc/sub.vtt"))
        assertTrue("Ceiling of 4 links with subtitles triggers satisfaction", controller.isSatisfied())
    }

    @Test
    fun testEarlySatisfactionControllerStrict720pAndSubtitlesGating() {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 1,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P720.value,
            requireSubtitles = true,
            requireDualQualities = false,
            require720p = true
        )
        val controller = EarlySatisfactionController(config)

        val link1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/v1080.m3u8", Qualities.P1080.value)
        val sub = createSubtitle("English", "https://hexa.su/sub.vtt")
        val link720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/v720.m3u8", Qualities.P720.value)

        // 1. Emit 1080p -> unsatisfied
        controller.onLinkEmitted(link1080)
        assertFalse("Cannot satisfy with 1080p when require720p is true", controller.isSatisfied())

        // 2. Emit subtitles -> still unsatisfied because 720p has not arrived
        controller.onSubtitleEmitted(sub)
        assertFalse("Cannot satisfy with subtitles + 1080p when require720p is true", controller.isSatisfied())

        // 3. Emit 720p -> 720p + subtitles present -> SATISFIED!
        val satisfied = controller.onLinkEmitted(link720)
        assertTrue("Must satisfy once 720p is emitted with subtitles", satisfied)
        assertTrue(controller.isSatisfied())
    }

    @Test
    fun testStreamDeduplicatorRetainsBoth1080pAnd720pVariantsWithCleanUrls() {
        val emittedList = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        // Both links share the EXACT SAME clean manifest URL (no ?quality=720p query parameter hack)
        val link1080 = createLink("VidLink", "VidLink HLS [1080p]", "https://vidlink.pro/master.m3u8", Qualities.P1080.value, type = ExtractorLinkType.M3U8)
        val link720 = createLink("VidLink", "VidLink HLS [720p]", "https://vidlink.pro/master.m3u8", Qualities.P720.value, type = ExtractorLinkType.M3U8)

        val res1 = deduplicator.emitDetailed(link1080)
        val res2 = deduplicator.emitDetailed(link720)

        assertEquals("1080p stream must be accepted as NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res1)
        assertEquals("720p stream with clean URL must be accepted as NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res2)
        assertEquals("Both 1080p and 720p streams must be preserved", 2, emittedList.size)
        assertEquals(Qualities.P1080.value, emittedList[0].quality)
        assertEquals(Qualities.P720.value, emittedList[1].quality)
        assertEquals("URLs must remain identical and clean for CDN playback", "https://vidlink.pro/master.m3u8", emittedList[1].url)
    }

    @Test
    fun testStreamDeduplicatorRetainsBothVariantsRegardlessOfArrivalOrder() {
        val emittedList = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        // 720p arrives FIRST, then 1080p
        val link720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.vc/stream.m3u8", Qualities.P720.value, type = ExtractorLinkType.M3U8)
        val link1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/stream.m3u8", Qualities.P1080.value, type = ExtractorLinkType.M3U8)

        val res1 = deduplicator.emitDetailed(link720)
        val res2 = deduplicator.emitDetailed(link1080)

        assertEquals("720p accepted as NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res1)
        assertEquals("1080p accepted as NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res2)
        assertEquals(2, deduplicator.getEmittedCount())
        assertEquals(2, emittedList.size)
    }

    @Test
    fun testStreamDeduplicatorDropsDuplicateSameQualityFromInferiorSource() {
        val emittedList = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        val vidlink1080 = createLink("VidLink", "VidLink HLS [1080p]", "https://shared-cdn.com/master.m3u8", Qualities.P1080.value, type = ExtractorLinkType.M3U8)
        val hexa1080 = createLink("HexaSU", "HexaSU HLS [1080p]", "https://shared-cdn.com/master.m3u8", Qualities.P1080.value, type = ExtractorLinkType.M3U8)

        assertEquals(StreamLinkOptimizer.DeduplicationResult.NEW, deduplicator.emitDetailed(vidlink1080))
        assertEquals("Inferior source at same quality must be dropped as duplicate", StreamLinkOptimizer.DeduplicationResult.DROPPED, deduplicator.emitDetailed(hexa1080))
        assertEquals(1, deduplicator.getEmittedCount())
        assertEquals("VidLink", emittedList.first().source)
    }

    @Test
    fun testIsStreamBetterStrictSotaQualityHierarchy() {
        val link720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/video.mp4", Qualities.P720.value, type = ExtractorLinkType.VIDEO)
        val link1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/video.mp4", Qualities.P1080.value, type = ExtractorLinkType.VIDEO)
        val link480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/video.mp4", Qualities.P480.value, type = ExtractorLinkType.VIDEO)
        val link576 = createLink("VidLink", "VidLink [576p]", "https://vidlink.pro/video.mp4", 576, type = ExtractorLinkType.VIDEO)
        val link1440 = createLink("VidLink", "VidLink [1440p]", "https://vidlink.pro/video.mp4", Qualities.P1440.value, type = ExtractorLinkType.VIDEO)
        val link4k = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/video.mp4", Qualities.P2160.value, type = ExtractorLinkType.VIDEO)
        val link360 = createLink("VidLink", "VidLink [360p]", "https://vidlink.pro/video.mp4", Qualities.P360.value, type = ExtractorLinkType.VIDEO)
        val linkUnknown = createLink("VidLink", "VidLink [Unknown]", "https://vidlink.pro/video.mp4", Qualities.Unknown.value, type = ExtractorLinkType.VIDEO)

        // Strict SOTA quality hierarchy: 720p > 1080p > 480p > 576p > 360p (> all below 720) > 1440p > 4K (> all above 1080) > Unknown
        assertTrue("720p must be better stream than 1080p", StreamLinkOptimizer.isStreamBetter(link720, link1080))
        assertFalse("1080p must NOT be better stream than 720p", StreamLinkOptimizer.isStreamBetter(link1080, link720))

        assertTrue("1080p must be better stream than 480p", StreamLinkOptimizer.isStreamBetter(link1080, link480))
        assertFalse("480p must NOT be better stream than 1080p", StreamLinkOptimizer.isStreamBetter(link480, link1080))

        assertTrue("480p must be better stream than 576p", StreamLinkOptimizer.isStreamBetter(link480, link576))
        assertTrue("576p must be better stream than 360p", StreamLinkOptimizer.isStreamBetter(link576, link360))
        assertTrue("360p (below 720) must be better stream than 1440p (above 1080)", StreamLinkOptimizer.isStreamBetter(link360, link1440))
        assertTrue("1440p must be better stream than 4K", StreamLinkOptimizer.isStreamBetter(link1440, link4k))
        assertTrue("4K must be better stream than Unknown", StreamLinkOptimizer.isStreamBetter(link4k, linkUnknown))
    }

    @Test
    fun testDirectMp4FileQualityUpgradePreserved() {
        val emittedList = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        val mp4_1080 = createLink("Scraper", "Direct [1080p]", "https://direct.cdn.com/movie.mp4", Qualities.P1080.value, type = ExtractorLinkType.VIDEO)
        val mp4_720 = createLink("Scraper", "Direct [720p]", "https://direct.cdn.com/movie.mp4", Qualities.P720.value, type = ExtractorLinkType.VIDEO)

        assertEquals(StreamLinkOptimizer.DeduplicationResult.NEW, deduplicator.emitDetailed(mp4_1080))
        assertEquals("For single MP4 file, 720p upgrades 1080p under user priority", StreamLinkOptimizer.DeduplicationResult.UPGRADED, deduplicator.emitDetailed(mp4_720))
        assertEquals(1, deduplicator.getEmittedCount())
        assertEquals(Qualities.P720.value, deduplicator.getEmittedLinks().first().quality)
    }

    @Test
    fun testPriorityStreamDispatcherDispatches720pAndSubtitlesFirst() = kotlinx.coroutines.runBlocking {
        val dispatchedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { dispatchedLinks.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 150L
        )

        val hexa1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/1080.m3u8", Qualities.P1080.value)
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidlink480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)

        // 1. HexaSU 1080p arrives first at t=0ms
        dispatcher.onLinkAccepted(hexa1080)
        assertTrue("1080p must be staged while waiting for 720p", dispatchedLinks.isEmpty())

        // 2. Subtitles arrive at t=20ms
        dispatcher.onSubtitleReceived()
        assertTrue("Still staged because 720p has not arrived yet", dispatchedLinks.isEmpty())

        // 3. VidLink 720p arrives at t=40ms
        dispatcher.onLinkAccepted(vidlink720)

        // VidLink 720p must be dispatched IMMEDIATELY as link #1 because 720p + subtitles are satisfied!
        assertTrue("Dispatcher must have emitted top stream", dispatcher.hasTopStreamEmitted())
        assertEquals("First dispatched link must be VidLink 720p", vidlink720, dispatchedLinks[0])
        assertEquals("Second dispatched link must be HexaSU 1080p", hexa1080, dispatchedLinks[1])

        // 4. Later arriving 480p link
        dispatcher.onLinkAccepted(vidlink480)
        assertEquals("Third dispatched link must be 480p", vidlink480, dispatchedLinks[2])
    }

    @Test
    fun testPriorityStreamDispatcherPrefersTopSourceRankVidLinkOverLowerRank720p() = kotlinx.coroutines.runBlocking {
        val dispatchedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { dispatchedLinks.add(it) },
            scope = this,
            stageWindowMs = 300L,
            subtitleGraceMs = 250L,
            topSourceGraceMs = 150L
        )

        val vidfast720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.pro/720.m3u8", Qualities.P720.value)
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)

        // 1. Subtitles are already present
        dispatcher.onSubtitleReceived()

        // 2. Lower-tier VidFast 720p (rank 70) arrives first at t=0ms
        dispatcher.onLinkAccepted(vidfast720)
        assertTrue("VidFast 720p must be staged briefly waiting for top-tier VidLink 100", dispatchedLinks.isEmpty())

        // 3. Top-tier VidLink 720p (rank 100) arrives at t=30ms
        kotlinx.coroutines.delay(30L)
        dispatcher.onLinkAccepted(vidlink720)

        // VidLink 720p must be dispatched IMMEDIATELY as link #1!
        assertTrue("Dispatcher must have emitted top stream", dispatcher.hasTopStreamEmitted())
        assertEquals("First dispatched link must be VidLink 720p", vidlink720, dispatchedLinks[0])
        assertEquals("Second dispatched link must be VidFast 720p", vidfast720, dispatchedLinks[1])
    }

    @Test
    fun testPriorityStreamDispatcherEmitsLowerRank720pAfterGraceTimeout() = kotlinx.coroutines.runBlocking {
        val dispatchedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { dispatchedLinks.add(it) },
            scope = this,
            stageWindowMs = 300L,
            subtitleGraceMs = 250L,
            topSourceGraceMs = 100L
        )

        val vidfast720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.pro/720.m3u8", Qualities.P720.value)

        // 1. Subtitles are already present
        dispatcher.onSubtitleReceived()

        // 2. VidFast 720p arrives at t=0ms
        dispatcher.onLinkAccepted(vidfast720)
        assertTrue("VidFast 720p must be staged briefly", dispatchedLinks.isEmpty())

        // 3. Wait for topSourceGraceMs to expire (100ms + 60ms buffer)
        kotlinx.coroutines.delay(160L)

        // VidFast 720p must be dispatched as link #1 after timeout since VidLink did not arrive
        assertTrue("Dispatcher must have emitted top stream after grace timeout", dispatcher.hasTopStreamEmitted())
        assertEquals("First dispatched link must be VidFast 720p", vidfast720, dispatchedLinks[0])
    }

    @Test
    fun testEarlySatisfactionControllerDetects720pFromUrl() {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 1,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P720.value,
            requireSubtitles = true,
            requireDualQualities = false,
            require720p = true
        )
        val controller = EarlySatisfactionController(config)

        val sub = createSubtitle("English", "https://example.com/sub.vtt")
        controller.onSubtitleEmitted(sub)

        // Link with quality only in URL, Unknown in link.quality and link.name
        val linkUrl720 = createLink("VidLink", "VidLink Server Fast", "https://vidlink.pro/video/720p/manifest.m3u8", Qualities.Unknown.value)
        val satisfied = controller.onLinkEmitted(linkUrl720)
        assertTrue("Must satisfy when 720p is detected from URL and subtitles present", satisfied)
        assertTrue(controller.has720p())
    }

    @Test
    fun testNumericQualityExtractionWithoutP() {
        val q720 = StreamLinkOptimizer.extractQualityFromText("VidLink 720 Server")
        val q1080 = StreamLinkOptimizer.extractQualityFromText("VidLink 1080 Server")
        val q2160 = StreamLinkOptimizer.extractQualityFromText("VidLink 2160 UHD")
        val q480 = StreamLinkOptimizer.extractQualityFromText("VidLink 480 SD")

        assertEquals(Qualities.P720.value, q720)
        assertEquals(Qualities.P1080.value, q1080)
        assertEquals(Qualities.P2160.value, q2160)
        assertEquals(Qualities.P480.value, q480)
    }

    @Test
    fun testDedicatedSubtitleProvidersNotCancelledOnEarlyVideoSatisfaction() = runBlocking {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 2,
            qualityThreshold = Qualities.P720.value,
            requireSubtitles = false,
            requireDualQualities = true,
            tier1DelayMs = 0L,
            tier2DelayMs = 50L
        )
        val controller = EarlySatisfactionController(config)
        val subtitleCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        val videoCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val tasks = listOf(
            // Tier 0 Fast Video: Emits 1080p and 720p immediately, achieving early video satisfaction
            PipelinedTask("FastVideo", LatencyTier.TIER_0, isVideo = true) {
                delay(20)
                controller.onLinkEmitted(createLink("FastVideo", "FastVideo [1080p]", "https://cdn.com/1080.m3u8", Qualities.P1080.value))
                controller.onLinkEmitted(createLink("FastVideo", "FastVideo [720p]", "https://cdn.com/720.m3u8", Qualities.P720.value))
            },
            // Tier 1 Subtitle: Takes 80ms to fetch multi-language subtitle tracks
            PipelinedTask("SubtitleProvider", LatencyTier.TIER_1, isVideo = false) {
                delay(80)
                subtitleCompleted.set(true)
                controller.onSubtitleEmitted(createSubtitle("English", "https://sub.com/en.srt"))
            },
            // Tier 1 Slow Video: Should be cancelled once early satisfaction is achieved
            PipelinedTask("SlowVideo", LatencyTier.TIER_1, isVideo = true) {
                try {
                    delay(500)
                } catch (e: CancellationException) {
                    videoCancelled.set(true)
                    throw e
                }
            }
        )

        val result = SpeculativePipeliner.executePipelined(tasks, config, controller)
        assertTrue(result)
        assertTrue("Dedicated subtitle provider must complete and not be cancelled early", subtitleCompleted.get())
        assertTrue("Slow video provider must be cancelled upon early satisfaction", videoCancelled.get())
    }

    @Test
    fun testDelimiterQualityExtraction() {
        val testCases = listOf(
            "https://cdn.example.com/video_720p.m3u8" to Qualities.P720.value,
            "https://cdn.example.com/video_1080p.m3u8" to Qualities.P1080.value,
            "https://cdn.example.com/video_480p.mp4" to Qualities.P480.value,
            "https://cdn.example.com/video_360p.mp4" to Qualities.P360.value,
            "https://cdn.example.com/video-720-stream" to Qualities.P720.value,
            "https://cdn.example.com/stream/1080/manifest.m3u8" to Qualities.P1080.value,
            "https://cdn.example.com/stream/720/index.m3u8" to Qualities.P720.value,
            "https://cdn.example.com/stream[720p].mp4" to Qualities.P720.value,
            "https://cdn.example.com/movie_720.mp4" to Qualities.P720.value,
            "https://cdn.example.com/movie_1080.mp4" to Qualities.P1080.value
        )

        for ((url, expectedQuality) in testCases) {
            val detected = StreamLinkOptimizer.extractQualityFromText(url)
            assertEquals("URL '$url' should detect quality $expectedQuality", expectedQuality, detected)
        }
    }

    @Test
    fun testTopTierSourcesDirectVideoQualityCoexistenceInDeduplicator() {
        val topSources = listOf(
            "VidLink" to 100,
            "HexaSU" to 90,
            "AutoEmbed" to 80,
            "VidFast" to 70,
            "VidEasy" to 60,
            "VidSrc" to 55
        )

        for ((sourceName, expectedRank) in topSources) {
            val emittedList = mutableListOf<ExtractorLink>()
            val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }
            val streamUrl = "https://cdn.example.com/direct/stream/movie123.mp4"

            val link720 = createLink(
                source = sourceName,
                name = "$sourceName [720p]",
                url = streamUrl,
                quality = Qualities.P720.value,
                type = ExtractorLinkType.VIDEO
            )
            val link1080 = createLink(
                source = sourceName,
                name = "$sourceName [1080p]",
                url = streamUrl,
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.VIDEO
            )

            assertEquals("$sourceName must have rank $expectedRank", expectedRank, StreamLinkOptimizer.getSourcePriorityRank(link720))

            val res1080 = deduplicator.emitDetailed(link1080)
            val res720 = deduplicator.emitDetailed(link720)

            assertEquals("$sourceName 1080p must be NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res1080)
            assertEquals("$sourceName 720p must upgrade 1080p rather than coexisting as duplicate URL", StreamLinkOptimizer.DeduplicationResult.UPGRADED, res720)

            assertEquals("$sourceName must deduplicate direct video to exactly 1 stream", 1, deduplicator.getEmittedCount())
            val emittedLinks = deduplicator.getEmittedLinks()
            assertEquals(1, emittedLinks.size)
            assertEquals(Qualities.P720.value, emittedLinks.first().quality)
            assertEquals(streamUrl, emittedLinks.first().url)
        }
    }

    @Test
    fun testPriorityStreamDispatcherDualStageOrder() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            fhdGraceMs = 100L,
            upstreamCallback = { emittedLinks.add(it) }
        )

        // Subtitles received first
        dispatcher.onSubtitleReceived()

        // 720p from VidLink (pinnacle rank 100) -> emitted immediately as #1
        val link720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/v.m3u8", Qualities.P720.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link720)
        assertEquals(1, emittedLinks.size)
        assertEquals(Qualities.P720.value, emittedLinks[0].quality)

        // 480p arrives while 1080p is still in flight -> staged in pendingBelowFhdLinks!
        val link480 = createLink("HexaSU", "HexaSU [480p]", "https://hexa.su/v_480.m3u8", Qualities.P480.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link480)
        assertEquals("480p must not be emitted before 1080p", 1, emittedLinks.size)

        // 1080p arrives -> emitted immediately as #2, and flushes 480p as #3
        val link1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/v_1080.m3u8", Qualities.P1080.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link1080)

        assertEquals("Both 1080p and staged 480p must now be emitted", 3, emittedLinks.size)
        assertEquals(Qualities.P720.value, emittedLinks[0].quality)
        assertEquals(Qualities.P1080.value, emittedLinks[1].quality)
        assertEquals(Qualities.P480.value, emittedLinks[2].quality)

        dispatcher.flush()
    }

    @Test
    fun testPriorityStreamDispatcherStages480pAnd4kUntil1080p() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            fhdGraceMs = 150L,
            upstreamCallback = { emittedLinks.add(it) }
        )

        dispatcher.onSubtitleReceived()

        val link720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/v.m3u8", Qualities.P720.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link720)

        // Lower and higher qualities arrive out of order
        val link480 = createLink("AutoEmbed", "AutoEmbed [480p]", "https://autoembed.cc/v480.m3u8", Qualities.P480.value, ExtractorLinkType.M3U8)
        val link4k = createLink("VidFast", "VidFast [4K]", "https://vidfast.vc/v4k.m3u8", Qualities.P2160.value, ExtractorLinkType.M3U8)

        dispatcher.onLinkAccepted(link480)
        dispatcher.onLinkAccepted(link4k)

        assertEquals("Neither 480p nor 4K should emit before 1080p", 1, emittedLinks.size)

        // 1080p arrives
        val link1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/v1080.m3u8", Qualities.P1080.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link1080)

        assertEquals(4, emittedLinks.size)
        assertEquals("Priority #1 must be 720p", Qualities.P720.value, emittedLinks[0].quality)
        assertEquals("Priority #2 must be 1080p", Qualities.P1080.value, emittedLinks[1].quality)
        assertEquals("Priority #3 must be 480p (below 720p beats above 1080p)", Qualities.P480.value, emittedLinks[2].quality)
        assertEquals("Priority #4 must be 4K (above 1080p)", Qualities.P2160.value, emittedLinks[3].quality)

        dispatcher.flush()
    }

    @Test
    fun testWidescreenResolutionPrioritization() {
        // Movies with 2.39:1 / 2.35:1 aspect ratios produce heights like 800 (1920x800 FHD) or 718/536 (1280x718/536 HD)
        val score718 = StreamLinkOptimizer.getQualityPriorityScore(718)
        val score800 = StreamLinkOptimizer.getQualityPriorityScore(800)
        val score480 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P480.value)
        val score2160 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P2160.value)

        assertEquals("718p (720p widescreen) must receive score 10000", 10000, score718)
        assertTrue("800p (1080p widescreen) must receive FHD tier score (8500-8999)", score800 in 8500..8999)

        // Monotonicity: 720p widescreen > 1080p widescreen > 480p > 4K
        assertTrue("718p widescreen (720p) must beat 800p widescreen (1080p)", score718 > score800)
        assertTrue("800p widescreen (1080p) must beat 480p", score800 > score480)
        assertTrue("480p must beat 4K", score480 > score2160)

        // isQualityPrioritized checks
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(718))
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(800))

        // PriorityStreamDispatcher resolution classification
        val link718 = createLink("VidLink", "VidLink 1280x718", "https://vidlink.pro/718.m3u8", 718)
        val link800 = createLink("VidLink", "VidLink 1920x800", "https://vidlink.pro/800.m3u8", 800)
        assertTrue(StreamLinkOptimizer.PriorityStreamDispatcher.is720p(link718))
        assertTrue(StreamLinkOptimizer.PriorityStreamDispatcher.is1080p(link800))
        assertFalse(StreamLinkOptimizer.PriorityStreamDispatcher.isBelow720p(link718))
        assertFalse(StreamLinkOptimizer.PriorityStreamDispatcher.isAbove1080p(link800))
    }

    @Test
    fun testPriorityStreamDispatcherStages4kWaitingFor480pAfter1080pEmitted() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            fhdGraceMs = 100L,
            sdGraceMs = 200L,
            upstreamCallback = { emittedLinks.add(it) }
        )

        dispatcher.onSubtitleReceived()

        // 1. Emit 720p (#1)
        val link720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/v720.m3u8", Qualities.P720.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link720)
        assertEquals(1, emittedLinks.size)

        // 2. Emit 1080p (#2)
        val link1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/v1080.m3u8", Qualities.P1080.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link1080)
        assertEquals(2, emittedLinks.size)

        // 3. 4K arrives AFTER 1080p is emitted, but 480p has NOT arrived yet -> must stage 4K waiting for 480p!
        val link4k = createLink("AutoEmbed", "AutoEmbed [4K]", "https://autoembed.cc/v4k.m3u8", Qualities.P2160.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link4k)
        assertEquals("4K must be staged waiting for 480p and not emitted immediately", 2, emittedLinks.size)

        // 4. 480p arrives within sdGraceMs -> emitted as #3, and flushes 4K as #4
        val link480 = createLink("VidFast", "VidFast [480p]", "https://vidfast.vc/v480.m3u8", Qualities.P480.value, ExtractorLinkType.M3U8)
        dispatcher.onLinkAccepted(link480)

        assertEquals("All 4 links must now be emitted", 4, emittedLinks.size)
        assertEquals("Priority #1 must be 720p", Qualities.P720.value, emittedLinks[0].quality)
        assertEquals("Priority #2 must be 1080p", Qualities.P1080.value, emittedLinks[1].quality)
        assertEquals("Priority #3 must be 480p", Qualities.P480.value, emittedLinks[2].quality)
        assertEquals("Priority #4 must be 4K", Qualities.P2160.value, emittedLinks[3].quality)

        dispatcher.flush()
    }

    @Test
    fun testEmitTopTierDualQualityStreamLinksForDirectVideo() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "VidLink",
            baseName = "VidLink Server 1",
            url = "https://vidlink.pro/stream/movie.mp4",
            referer = "https://vidlink.pro/",
            headers = mapOf("Referer" to "https://vidlink.pro/"),
            streamType = ExtractorLinkType.VIDEO,
            generatedLinks = null,
            callback = { emitted.add(it) }
        )

        assertEquals("Must emit exactly 1 link for direct video without synthetic companion", 1, emitted.size)
        assertEquals("Direct video URL must match", "https://vidlink.pro/stream/movie.mp4", emitted[0].url)
        assertEquals("Direct video source must match", "VidLink", emitted[0].source)
        assertEquals("Direct video type must match", ExtractorLinkType.VIDEO, emitted[0].type)
        assertTrue("Links must have sota_dual_quality tag in extractorData", emitted[0].extractorData?.contains("sota_dual_quality") == true)
    }

    @Test
    fun testEmitTopTierDualQualityStreamLinksSynthesizes720WhenOnly1080Present() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val link1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/v.m3u8", Qualities.P1080.value, ExtractorLinkType.M3U8)
        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "HexaSU",
            baseName = "HexaSU",
            url = "https://hexa.su/v.m3u8",
            referer = "https://hexa.su/",
            streamType = ExtractorLinkType.M3U8,
            generatedLinks = listOf(link1080),
            callback = { emitted.add(it) }
        )

        assertEquals("Must emit exactly 1 link (only genuine 1080p variant without fake 720p companion)", 1, emitted.size)
        assertEquals("Only genuine 1080p variant must be emitted", Qualities.P1080.value, emitted[0].quality)
        assertEquals("URL must match original variant", link1080.url, emitted[0].url)
    }

    @Test
    fun testEmitTopTierDualQualityStreamLinksSynthesizes1080WhenOnly720Present() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val link720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/v.m3u8", Qualities.P720.value, ExtractorLinkType.M3U8)
        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "AutoEmbed",
            baseName = "AutoEmbed",
            url = "https://autoembed.cc/v.m3u8",
            referer = "https://autoembed.cc/",
            streamType = ExtractorLinkType.M3U8,
            generatedLinks = listOf(link720),
            callback = { emitted.add(it) }
        )

        assertEquals("Must emit exactly 1 link (only genuine 720p variant without fake 1080p companion)", 1, emitted.size)
        assertEquals("Only genuine 720p variant must be emitted", Qualities.P720.value, emitted[0].quality)
        assertEquals("URL must match original variant", link720.url, emitted[0].url)
    }

    @Test
    fun testEmitTopTierDualQualityStreamLinksAllSixTopSourcesDirectVideo() = runBlocking {
        val topSources = listOf(
            "VidLink" to 100,
            "HexaSU" to 90,
            "AutoEmbed" to 80,
            "VidFast" to 70,
            "VidEasy" to 60,
            "VidSrc" to 55
        )

        for ((srcName, expectedRank) in topSources) {
            val emitted = mutableListOf<ExtractorLink>()
            StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
                source = srcName,
                baseName = srcName,
                url = "https://cdn.example.com/$srcName/stream.mp4",
                referer = "https://example.com/",
                streamType = ExtractorLinkType.VIDEO,
                generatedLinks = null,
                callback = { emitted.add(it) }
            )

            assertEquals("$srcName must emit exactly 1 authentic link", 1, emitted.size)
            assertEquals("$srcName must match expected rank $expectedRank", expectedRank, StreamLinkOptimizer.getSourcePriorityRank(emitted[0]))
            assertEquals("$srcName URL must match", "https://cdn.example.com/$srcName/stream.mp4", emitted[0].url)
        }
    }

    @Test
    fun testEmitTopTierDualQualityStreamLinksSynthesizesFromHighestAvailableWhenBoth720And1080Missing() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val link360 = createLink("HexaSU", "HexaSU [360p]", "https://hexa.su/360.m3u8", Qualities.P360.value, ExtractorLinkType.M3U8)
        val link480 = createLink("HexaSU", "HexaSU [480p]", "https://hexa.su/480.m3u8", Qualities.P480.value, ExtractorLinkType.M3U8)

        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "HexaSU",
            baseName = "HexaSU",
            url = "https://hexa.su/master.m3u8",
            referer = "https://hexa.su/",
            streamType = ExtractorLinkType.M3U8,
            generatedLinks = listOf(link360, link480),
            callback = { emitted.add(it) }
        )

        assertEquals("Must emit exactly 2 genuine variants (480p and 360p)", 2, emitted.size)
        assertEquals("Priority #1 must be 480p", Qualities.P480.value, emitted[0].quality)
        assertEquals("Priority #2 must be 360p", Qualities.P360.value, emitted[1].quality)
        assertTrue("Must not synthesize 720p or 1080p companions", emitted.none { it.quality == Qualities.P720.value || it.quality == Qualities.P1080.value })
    }

    @Test
    fun testEmitTopTierDualQualityStreamLinksResolvesQualityZeroLink() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        // Link with quality = 0 (Qualities.Unknown.value) but name has "[720p]"
        val linkWithZeroQuality = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/stream.m3u8", 0, ExtractorLinkType.M3U8)

        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "AutoEmbed",
            baseName = "AutoEmbed",
            url = "https://autoembed.cc/stream.m3u8",
            referer = "https://autoembed.cc/",
            streamType = ExtractorLinkType.M3U8,
            generatedLinks = listOf(linkWithZeroQuality),
            callback = { emitted.add(it) }
        )

        assertEquals("Must emit exactly 1 resolved link without synthetic companion", 1, emitted.size)
        assertEquals("Priority #1 must have resolved quality 720", Qualities.P720.value, emitted[0].quality)
    }

    @Test
    fun testCreateQualityCompanionFormattingAndTags() {
        val original = createLink("VidLink", "VidLink Server 1 [1080p]", "https://vidlink.pro/stream.m3u8", Qualities.P1080.value)
        @Suppress("DEPRECATION")
        val companion720 = StreamLinkOptimizer.createQualityCompanion(original, Qualities.P720.value)

        assertEquals("VidLink Server 1 [720p]", companion720.name)
        assertEquals(Qualities.P720.value, companion720.quality)
        assertEquals(original.url, companion720.url)
        assertEquals(original.source, companion720.source)
        assertTrue("Companion must have sota_dual_quality tag", companion720.extractorData?.contains("sota_dual_quality") == true)
    }

    @Test
    fun testPriorityStreamDispatcherDoesNotStarveOnMultiple720pStreams() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 300L,
            subtitleGraceMs = 150L,
            topSourceGraceMs = 100L
        )

        val link1 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/1.m3u8", Qualities.P720.value)
        val link2 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/2.m3u8", Qualities.P720.value)
        val link3 = createLink("VidFast", "VidFast [720p]", "https://vidfast.vc/3.m3u8", Qualities.P720.value)

        dispatcher.onLinkAccepted(link1)
        delay(60L)
        dispatcher.onLinkAccepted(link2)
        delay(60L)
        dispatcher.onLinkAccepted(link3)

        // Total elapsed since link1: 120ms. Wait for original 150ms timer to fire:
        delay(80L) // Total 200ms elapsed (> 150ms)

        assertTrue("Dispatcher must have emitted top stream without timer starvation", dispatcher.hasTopStreamEmitted())
        assertTrue("Emitted links must not be empty", emitted.isNotEmpty())
        assertEquals("Highest rank among arrived (HexaSU 90) must be emitted first", Qualities.P720.value, emitted[0].quality)
        assertTrue("Emitted #1 must be HexaSU", emitted[0].source.contains("HexaSU"))

        dispatcher.flush()
    }

    @Test
    fun testExactSotaTopSourcesAndDualQuality12SequenceHierarchy() {
        // User Specification:
        // VidLink 100 with 720 > HexaSU 90 with 720 > AutoEmbed 80 with 720 > VidFast 70 with 720 > VidEasy 60 with 720 > VidSrc 55 with 720
        // then > VidLink with 1080 > HexaSU with 1080 > AutoEmbed with 1080 > VidFast with 1080 > VidEasy with 1080 > VidSrc with 1080

        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val hexa720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)
        val vidfast720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.vc/720.m3u8", Qualities.P720.value)
        val videasy720 = createLink("VidEasy", "VidEasy [720p]", "https://player.videasy.to/720.m3u8", Qualities.P720.value)
        val vidsrc720 = createLink("VidSrc", "VidSrc [720p]", "https://vidsrc.xyz/720.m3u8", Qualities.P720.value)

        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val hexa1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/1080.m3u8", Qualities.P1080.value)
        val autoembed1080 = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://autoembed.cc/1080.m3u8", Qualities.P1080.value)
        val vidfast1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/1080.m3u8", Qualities.P1080.value)
        val videasy1080 = createLink("VidEasy", "VidEasy [1080p]", "https://player.videasy.to/1080.m3u8", Qualities.P1080.value)
        val vidsrc1080 = createLink("VidSrc", "VidSrc [1080p]", "https://vidsrc.xyz/1080.m3u8", Qualities.P1080.value)

        val expectedOrder = listOf(
            vidlink720, hexa720, autoembed720, vidfast720, videasy720, vidsrc720,
            vidlink1080, hexa1080, autoembed1080, vidfast1080, videasy1080, vidsrc1080
        )

        // 1. Verify strict pairwise inequality of scores
        for (i in 0 until expectedOrder.size - 1) {
            val high = expectedOrder[i]
            val low = expectedOrder[i + 1]
            val scoreHigh = StreamLinkOptimizer.getStreamCompositeScore(high)
            val scoreLow = StreamLinkOptimizer.getStreamCompositeScore(low)
            assertTrue(
                "Expected ${high.source} ${high.quality}p ($scoreHigh) > ${low.source} ${low.quality}p ($scoreLow)",
                scoreHigh > scoreLow
            )
        }

        // 2. Verify sorting an arbitrary permutation produces the exact 12-element sequence
        val shuffled = expectedOrder.shuffled()
        val sorted = shuffled.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals(expectedOrder, sorted)

        // 3. Verify tiebreakers can NEVER invert tiers:
        // Even with max bitrate (50 Mbps) + REMUX + DV + Atmos, VidLink 1080p MUST NOT beat lowest top 720p (VidSrc 720p)
        val vidlink1080SuperMega = createLink(
            "VidLink",
            "[1080p] [REMUX] [DV] [50 Mbps] [Atmos] VidLink Mega",
            "https://vidlink.pro/1080_heavy.m3u8",
            Qualities.P1080.value
        )
        val vidsrc720Plain = createLink(
            "VidSrc",
            "VidSrc Basic",
            "https://vidsrc.xyz/720.m3u8",
            Qualities.P720.value
        )
        assertTrue(
            "VidSrc 720p plain must strictly beat VidLink 1080p with max tiebreakers",
            StreamLinkOptimizer.getStreamCompositeScore(vidsrc720Plain) > StreamLinkOptimizer.getStreamCompositeScore(vidlink1080SuperMega)
        )
    }

    @Test
    fun testProcessTopTierDualQualityStreamExpansion() {
        val guardedKeys = mutableSetOf<String>()
        val emitted = mutableListOf<ExtractorLink>()

        val rawVidLink1080 = createLink("VidLink", "VidLink Fast", "https://vidlink.pro/video.mp4", Qualities.P1080.value)
        val handled = StreamLinkOptimizer.processTopTierDualQualityStream(rawVidLink1080, guardedKeys) { emitted.add(it) }

        assertTrue("Must be handled as top tier", handled)
        assertEquals("Must emit exactly 1 link without synthetic companion expansion", 1, emitted.size)
        assertEquals(Qualities.P1080.value, emitted[0].quality)
        assertEquals("https://vidlink.pro/video.mp4", emitted[0].url)

        // Repeating call with same stream key must NOT duplicate/re-expand
        val emitted2 = mutableListOf<ExtractorLink>()
        val handledSecond = StreamLinkOptimizer.processTopTierDualQualityStream(rawVidLink1080, guardedKeys) { emitted2.add(it) }
        assertFalse("Second call with same key must return false", handledSecond)
        assertEquals("Second call only emits the single stream as fallback without expansion", 1, emitted2.size)
    }

    @Test
    fun testPriorityStreamDispatcherConcurrentArrivalExact12Sequence() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 300L,
            fhdGraceMs = 150L,
            sdGraceMs = 100L
        )

        // Subtitles received early
        dispatcher.onSubtitleReceived()

        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)

        val hexa720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)
        val hexa1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/1080.m3u8", Qualities.P1080.value)

        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)
        val autoembed1080 = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://autoembed.cc/1080.m3u8", Qualities.P1080.value)

        val vidfast720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.vc/720.m3u8", Qualities.P720.value)
        val vidfast1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/1080.m3u8", Qualities.P1080.value)

        val videasy720 = createLink("VidEasy", "VidEasy [720p]", "https://player.videasy.to/720.m3u8", Qualities.P720.value)
        val videasy1080 = createLink("VidEasy", "VidEasy [1080p]", "https://player.videasy.to/1080.m3u8", Qualities.P1080.value)

        val vidsrc720 = createLink("VidSrc", "VidSrc [720p]", "https://vidsrc.xyz/720.m3u8", Qualities.P720.value)
        val vidsrc1080 = createLink("VidSrc", "VidSrc [1080p]", "https://vidsrc.xyz/1080.m3u8", Qualities.P1080.value)

        // 1. VidLink finishes at t=0ms and emits both 720p and 1080p
        dispatcher.onLinkAccepted(vidlink720)
        dispatcher.onLinkAccepted(vidlink1080)

        // VidLink 720p must be emitted immediately, but VidLink 1080p MUST BE HELD in pending1080Links
        assertEquals("At t=0, only VidLink 720p should be emitted; 1080p must be staged", 1, emittedLinks.size)
        assertEquals(vidlink720, emittedLinks[0])

        // 2. HexaSU finishes at t=30ms and emits both
        delay(30L)
        dispatcher.onLinkAccepted(hexa720)
        dispatcher.onLinkAccepted(hexa1080)
        assertEquals("At t=30ms, HexaSU 720p must emit immediately ahead of 1080p", 2, emittedLinks.size)
        assertEquals(hexa720, emittedLinks[1])

        // 3. AutoEmbed finishes at t=60ms
        delay(30L)
        dispatcher.onLinkAccepted(autoembed720)
        dispatcher.onLinkAccepted(autoembed1080)
        assertEquals(3, emittedLinks.size)
        assertEquals(autoembed720, emittedLinks[2])

        // 4. VidFast finishes at t=90ms
        delay(30L)
        dispatcher.onLinkAccepted(vidfast720)
        dispatcher.onLinkAccepted(vidfast1080)
        assertEquals(4, emittedLinks.size)
        assertEquals(vidfast720, emittedLinks[3])

        // 5. VidEasy finishes at t=120ms
        delay(30L)
        dispatcher.onLinkAccepted(videasy720)
        dispatcher.onLinkAccepted(videasy1080)
        assertEquals(5, emittedLinks.size)
        assertEquals(videasy720, emittedLinks[4])

        // 6. VidSrc finishes at t=150ms
        delay(30L)
        dispatcher.onLinkAccepted(vidsrc720)
        dispatcher.onLinkAccepted(vidsrc1080)
        assertEquals("All 6 top-tier 720p streams must be emitted before ANY 1080p stream", 6, emittedLinks.size)
        assertEquals(vidsrc720, emittedLinks[5])

        // 7. Wait for top720GraceMs timer to fire and release 1080p tier
        delay(180L)

        assertEquals("After grace timer, all 12 streams must be emitted in exact SOTA sequence", 12, emittedLinks.size)

        val expectedExactOrder = listOf(
            vidlink720, hexa720, autoembed720, vidfast720, videasy720, vidsrc720,
            vidlink1080, hexa1080, autoembed1080, vidfast1080, videasy1080, vidsrc1080
        )
        assertEquals(expectedExactOrder, emittedLinks)

        dispatcher.flush()
    }

    @Test
    fun testSecondarySourcesHeldUntilTopTierDispatched() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 200L,
            fhdGraceMs = 100L
        )

        dispatcher.onSubtitleReceived()

        val moviebox720 = createLink("MovieBox", "MovieBox [720p]", "https://moviebox.ph/720.m3u8", Qualities.P720.value)
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)

        // Secondary source arrives first at t=0
        dispatcher.onLinkAccepted(moviebox720)
        assertTrue("Secondary source must be staged while top tier is pending", emitted.isEmpty())

        // Top-tier VidLink arrives at t=30ms
        delay(30L)
        dispatcher.onLinkAccepted(vidlink720)

        // VidLink 720p must be emitted as #1
        assertEquals("VidLink 720p must be emitted first", 1, emitted.size)
        assertEquals(vidlink720, emitted[0])

        dispatcher.flush()
        assertEquals("After flush, MovieBox must follow VidLink", 2, emitted.size)
        assertEquals(moviebox720, emitted[1])
    }

    @Test
    fun testPriorityStreamDispatcherOutOfOrder720pArrivalBufferedUntilHigherRankArrives() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 300L,
            fhdGraceMs = 150L,
            sdGraceMs = 100L
        )

        // Subtitles received early
        dispatcher.onSubtitleReceived()

        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)

        val hexa720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)
        val hexa1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/1080.m3u8", Qualities.P1080.value)

        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)
        val autoembed1080 = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://autoembed.cc/1080.m3u8", Qualities.P1080.value)

        val vidfast720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.vc/720.m3u8", Qualities.P720.value)
        val vidfast1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/1080.m3u8", Qualities.P1080.value)

        val videasy720 = createLink("VidEasy", "VidEasy [720p]", "https://player.videasy.to/720.m3u8", Qualities.P720.value)
        val videasy1080 = createLink("VidEasy", "VidEasy [1080p]", "https://player.videasy.to/1080.m3u8", Qualities.P1080.value)

        val vidsrc720 = createLink("VidSrc", "VidSrc [720p]", "https://vidsrc.xyz/720.m3u8", Qualities.P720.value)
        val vidsrc1080 = createLink("VidSrc", "VidSrc [1080p]", "https://vidsrc.xyz/1080.m3u8", Qualities.P1080.value)

        // 1. VidLink finishes at t=0ms and emits both 720p and 1080p
        dispatcher.onLinkAccepted(vidlink720)
        dispatcher.onLinkAccepted(vidlink1080)
        assertEquals("VidLink 720p should emit immediately", 1, emittedLinks.size)
        assertEquals(vidlink720, emittedLinks[0])

        // 2. Out-of-order arrival: AutoEmbed (rank 80) arrives BEFORE HexaSU (rank 90) at t=20ms
        delay(20L)
        dispatcher.onLinkAccepted(autoembed720)
        dispatcher.onLinkAccepted(autoembed1080)
        assertEquals("AutoEmbed 720p must be held in pendingTop720Links because HexaSU (90) is pending", 1, emittedLinks.size)

        // 3. HexaSU (rank 90) arrives at t=50ms
        delay(30L)
        dispatcher.onLinkAccepted(hexa720)
        dispatcher.onLinkAccepted(hexa1080)
        // HexaSU 720p must emit, and AutoEmbed 720p must immediately drain!
        assertEquals("HexaSU 720p must emit and drain AutoEmbed 720p immediately", 3, emittedLinks.size)
        assertEquals(hexa720, emittedLinks[1])
        assertEquals(autoembed720, emittedLinks[2])

        // 4. VidFast (rank 70) arrives at t=70ms (in-order with respect to completed 100, 90, 80)
        delay(20L)
        dispatcher.onLinkAccepted(vidfast720)
        dispatcher.onLinkAccepted(vidfast1080)
        assertEquals("VidFast 70 should emit immediately", 4, emittedLinks.size)
        assertEquals(vidfast720, emittedLinks[3])

        // 5. Out-of-order arrival: VidSrc (rank 55) arrives BEFORE VidEasy (rank 60) at t=90ms
        delay(20L)
        dispatcher.onLinkAccepted(vidsrc720)
        dispatcher.onLinkAccepted(vidsrc1080)
        assertEquals("VidSrc 720p must be held because VidEasy (60) has not completed", 4, emittedLinks.size)

        // 6. VidEasy (rank 60) arrives at t=120ms
        delay(30L)
        dispatcher.onLinkAccepted(videasy720)
        dispatcher.onLinkAccepted(videasy1080)
        // VidEasy 720p emits and VidSrc 720p drains
        assertEquals("All 6 top-tier 720p streams must now be emitted in exact rank order", 6, emittedLinks.size)
        assertEquals(videasy720, emittedLinks[4])
        assertEquals(vidsrc720, emittedLinks[5])

        // 7. Wait for top720GraceMs timer to fire and release 1080p tier
        delay(200L)

        assertEquals("After grace timer, all 12 streams must be emitted in exact SOTA sequence", 12, emittedLinks.size)

        val expectedExactOrder = listOf(
            vidlink720, hexa720, autoembed720, vidfast720, videasy720, vidsrc720,
            vidlink1080, hexa1080, autoembed1080, vidfast1080, videasy1080, vidsrc1080
        )
        assertEquals(expectedExactOrder, emittedLinks)

        dispatcher.flush()
    }

    @Test
    fun testSpeculativePipelinerTopTierTasksShieldedFromActiveVideoCancellation() = runBlocking {
        val hexasuFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val genericScraperCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 2,
            qualityThreshold = Qualities.P720.value,
            requireSubtitles = true,
            minSubtitles = 1,
            requireDualQualities = true,
            require720p = true,
            tier1DelayMs = 0L,
            tier2DelayMs = 0L
        )
        val controller = EarlySatisfactionController(config)

        val tasks = listOf(
            // VidLink (100) finishes at 10ms, emits 720p + 1080p + subtitles -> achieves early satisfaction
            PipelinedTask("vidlink", LatencyTier.TIER_0, isVideo = true, priorityBoost = 100f) {
                delay(10)
                controller.onSubtitleEmitted(createSubtitle("English"))
                controller.onLinkEmitted(createLink("VidLink", "VidLink 720p", "https://vidlink.pro/720.m3u8", Qualities.P720.value))
                controller.onLinkEmitted(createLink("VidLink", "VidLink 1080p", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value))
            },
            // HexaSU (90) is top-tier (score 90 >= 55f). Even after VidLink satisfies controller,
            // HexaSU must NOT be cancelled by cancelActiveVideoJobs()!
            PipelinedTask("HexaSU", LatencyTier.TIER_0, isVideo = true, priorityBoost = 90f) {
                delay(50)
                controller.onLinkEmitted(createLink("HexaSU", "HexaSU 720p", "https://hexa.su/720.m3u8", Qualities.P720.value))
                hexasuFinished.set(true)
            },
            // Generic scraper is secondary (score 0f < 55f). When early satisfaction is reached, it SHOULD be cancelled.
            PipelinedTask("generic_scraper", LatencyTier.TIER_0, isVideo = true, priorityBoost = 0f) {
                try {
                    delay(100)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    genericScraperCancelled.set(true)
                    throw e
                }
            }
        )

        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller,
            maxConcurrencyOverride = 10
        )

        assertTrue("Pipeline should return true", result)
        assertTrue("Controller must be satisfied", controller.isSatisfied())
        assertTrue("Top-tier HexaSU must finish and NOT be cancelled", hexasuFinished.get())
        assertTrue("Non-top-tier generic scraper must be cancelled on early satisfaction", genericScraperCancelled.get())
    }

    @Test
    fun testPriorityStreamDispatcherOutOfOrderArrivalStrictOrderingDuringGrace() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 150L,
            fhdGraceMs = 100L
        )

        // Subtitles received early
        dispatcher.onSubtitleReceived()

        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)
        val hexasu720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)

        // 1. VidLink 720p and 1080p arrive at t=0
        dispatcher.onLinkAccepted(vidlink720)
        dispatcher.onLinkAccepted(vidlink1080)
        assertEquals("VidLink 720p should emit immediately", 1, emittedLinks.size)
        assertEquals(vidlink720, emittedLinks[0])

        // 2. AutoEmbed 720p (rank 80) arrives at t=20ms (out of order before HexaSU 90)
        delay(20L)
        dispatcher.onLinkAccepted(autoembed720)
        assertEquals("AutoEmbed 720p must be held in pendingTop720Links because HexaSU (90) is pending", 1, emittedLinks.size)

        // 3. HexaSU 720p (rank 90) arrives at t=50ms
        delay(30L)
        dispatcher.onLinkAccepted(hexasu720)
        assertEquals("HexaSU 720p must emit and drain AutoEmbed 720p immediately in priority order", 3, emittedLinks.size)
        assertEquals(hexasu720, emittedLinks[1])
        assertEquals(autoembed720, emittedLinks[2])

        // 4. Wait for top720GraceMs (150L) to expire, releasing 1080p
        delay(120L)
        assertEquals("VidLink 1080p must emit after top 720p grace period expires", 4, emittedLinks.size)
        assertEquals(vidlink1080, emittedLinks[3])

        // Dispatched order must be VidLink 720p -> HexaSU 720p -> AutoEmbed 720p -> VidLink 1080p
        val expectedOrder = listOf(vidlink720, hexasu720, autoembed720, vidlink1080)
        assertEquals(expectedOrder, emittedLinks)

        dispatcher.flush()
    }

    @Test
    fun testPriorityStreamDispatcherFhdGraceNotBypassedWhenNo1080pEmitted() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 80L,
            fhdGraceMs = 120L,
            sdGraceMs = 100L
        )

        dispatcher.onSubtitleReceived()

        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidsrc480 = createLink("VidSrc", "VidSrc [480p]", "https://vidsrc.xyz/480.mp4", Qualities.P480.value)
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)

        // 1. VidLink 720p emitted immediately at t=0
        dispatcher.onLinkAccepted(vidlink720)
        assertEquals(1, emittedLinks.size)
        assertEquals(vidlink720, emittedLinks[0])

        // 2. VidSrc 480p arrives at t=20ms (staged in pendingBelowFhd waiting for 1080p)
        delay(20L)
        dispatcher.onLinkAccepted(vidsrc480)
        assertEquals("480p must not emit before 1080p", 1, emittedLinks.size)

        // 3. At t=60ms (before fhdGraceMs expires at t=140ms), 480p must STILL NOT be emitted!
        delay(40L)
        assertEquals("480p must still wait for fhdGraceMs to expire when no 1080p was emitted", 1, emittedLinks.size)

        // 4. VidLink 1080p arrives during fhdGraceMs at t=80ms
        delay(20L)
        dispatcher.onLinkAccepted(vidlink1080)

        // VidLink 1080p must emit, and then release 480p!
        assertEquals("VidLink 1080p must emit and then flush 480p", 3, emittedLinks.size)
        assertEquals(vidlink1080, emittedLinks[1])
        assertEquals(vidsrc480, emittedLinks[2])

        dispatcher.flush()
    }

    @Test
    fun testPriorityStreamDispatcherOutOfOrder3WayArrivalStrictOrderingDuringGrace() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 260L,
            fhdGraceMs = 100L
        )

        dispatcher.onSubtitleReceived()

        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val vidsrc720 = createLink("VidSrc", "VidSrc [720p]", "https://vidsrc.xyz/720.m3u8", Qualities.P720.value)
        val videasy720 = createLink("VidEasy", "VidEasy [720p]", "https://player.videasy.to/720.m3u8", Qualities.P720.value)
        val vidfast720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.vc/720.m3u8", Qualities.P720.value)
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)
        val hexasu720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)

        // 1. VidLink finishes at t=0
        dispatcher.onLinkAccepted(vidlink720)
        dispatcher.onLinkAccepted(vidlink1080)
        assertEquals(1, emittedLinks.size)

        // 2. VidSrc (rank 55) arrives at t=20ms (reversed order)
        delay(20L)
        dispatcher.onLinkAccepted(vidsrc720)
        assertEquals("VidSrc 720p must be held", 1, emittedLinks.size)

        // 3. VidEasy (rank 60) arrives at t=40ms
        delay(20L)
        dispatcher.onLinkAccepted(videasy720)
        assertEquals("VidEasy 60 and VidSrc 55 must be held", 1, emittedLinks.size)

        // 4. VidFast (rank 70) arrives at t=60ms
        delay(20L)
        dispatcher.onLinkAccepted(vidfast720)
        assertEquals("VidFast 70, VidEasy 60, VidSrc 55 must be held", 1, emittedLinks.size)

        // 5. AutoEmbed (rank 80) arrives at t=80ms
        delay(20L)
        dispatcher.onLinkAccepted(autoembed720)
        assertEquals("AutoEmbed 80, VidFast 70, VidEasy 60, VidSrc 55 held for HexaSU 90", 1, emittedLinks.size)

        // 6. HexaSU (rank 90) arrives at t=100ms
        delay(20L)
        dispatcher.onLinkAccepted(hexasu720)
        // HexaSU 90 arrives, so 90 emits, which cascades and unblocks 80, 70, 60, and 55!
        assertEquals("HexaSU 90 must emit, unblocking AutoEmbed 80, VidFast 70, VidEasy 60, and VidSrc 55 in exact rank order", 6, emittedLinks.size)
        assertEquals(hexasu720, emittedLinks[1])
        assertEquals(autoembed720, emittedLinks[2])
        assertEquals(vidfast720, emittedLinks[3])
        assertEquals(videasy720, emittedLinks[4])
        assertEquals(vidsrc720, emittedLinks[5])

        // 7. Wait for grace timer to release 1080p
        delay(180L)
        assertEquals("VidLink 1080p emitted after grace timer", 7, emittedLinks.size)
        assertEquals(vidlink1080, emittedLinks[6])

        dispatcher.flush()
    }

    @Test
    fun testPriorityStreamDispatcherTopStream1080pDoesNotBlockSubsequent720p() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 50L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 150L,
            fhdGraceMs = 100L
        )

        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val hexasu720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)

        // VidLink only has 1080p, arrives at t=0
        dispatcher.onLinkAccepted(vidlink1080)
        // Wait for stageWindow to emit VidLink 1080p as topStream
        delay(80L)
        assertEquals("VidLink 1080p emitted as topStream", 1, emittedLinks.size)
        assertEquals(vidlink1080, emittedLinks[0])

        // Now HexaSU arrives with 720p. Because VidLink (100) already emitted as topStream,
        // HexaSU (90) must NOT be blocked waiting for VidLink 720p!
        dispatcher.onLinkAccepted(hexasu720)
        assertEquals("HexaSU 720p must emit immediately without waiting for rank 100", 2, emittedLinks.size)
        assertEquals(hexasu720, emittedLinks[1])

        dispatcher.flush()
    }

    @Test
    fun testPriorityStreamDispatcherDisabledProviderDoesNotStallLowerRankSources() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        // VidLink (100) is DISABLED. Only HexaSU (90), AutoEmbed (80), etc. are active
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 50L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 500L,
            activeTopRanks = setOf(90, 80, 70, 60, 55)
        )

        val hexasu720 = createLink("HexaSU", "HexaSU [720p]", "https://hexa.su/720.m3u8", Qualities.P720.value)
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)

        // Subtitles present
        dispatcher.onSubtitleReceived()

        // HexaSU 720p arrives at t=0. Because VidLink 100 is disabled, HexaSU 90 is the top active rank
        // and must NOT be stalled waiting for rank 100!
        dispatcher.onLinkAccepted(hexasu720)
        assertEquals("HexaSU 720p must emit immediately without waiting for disabled VidLink 100", 1, emittedLinks.size)
        assertEquals(hexasu720, emittedLinks[0])

        // AutoEmbed 720p arrives next. Since HexaSU already emitted, AutoEmbed emits immediately
        dispatcher.onLinkAccepted(autoembed720)
        assertEquals("AutoEmbed 720p must emit immediately", 2, emittedLinks.size)
        assertEquals(autoembed720, emittedLinks[1])

        dispatcher.flush()
    }

    @Test
    fun testPriorityStreamDispatcherDynamicHold1080pWhile720pInFlightAndImmediateReleaseOnComplete() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        var autoembedInFlight = true
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 50L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 1500L,
            activeTopRanks = setOf(80, 70),
            isRankInFlight = { rank -> if (rank == 80) autoembedInFlight else false }
        )

        val vidfast1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/1080.m3u8", Qualities.P1080.value)
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)

        dispatcher.onSubtitleReceived()

        // VidFast 1080p arrives first at t=0, but higher-priority AutoEmbed 720p (rank 80) is in flight
        dispatcher.onLinkAccepted(vidfast1080)
        // Staged as topStream waiting for 720p or grace
        delay(80L)
        // Even after stage window, VidFast 1080p is held dynamically in pending1080Links because AutoEmbed 720p is in flight
        assertTrue("VidFast 1080p must be held while higher-ranked 720p is in-flight", emittedLinks.isEmpty())

        // AutoEmbed 720p arrives at t=100ms
        dispatcher.onLinkAccepted(autoembed720)
        autoembedInFlight = false
        dispatcher.markRankCompleted(80)

        // AutoEmbed 720p must emit first, and immediately unblock VidFast 1080p
        assertEquals("AutoEmbed 720p and VidFast 1080p must both be emitted", 2, emittedLinks.size)
        assertEquals("Priority #1 must be AutoEmbed 720p", autoembed720, emittedLinks[0])
        assertEquals("Priority #2 must be VidFast 1080p", vidfast1080, emittedLinks[1])

        dispatcher.flush()
    }

    // =========================================================================
    // SOTA Authentic Stream Quality Verification: R1, R2, R3
    // =========================================================================

    @Test
    fun testR1NoUrlDuplicationForDirectVideoFiles() = runBlocking {
        // 1. emitTopTierDualQualityStreamLinks with direct MP4 URL must emit exactly ONE link
        val emitted = mutableListOf<ExtractorLink>()
        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "VidLink",
            baseName = "VidLink Direct",
            url = "https://cdn.example.com/movie.mp4",
            referer = "https://vidlink.pro/",
            streamType = ExtractorLinkType.VIDEO,
            generatedLinks = null,
            callback = { emitted.add(it) }
        )
        assertEquals("Direct MP4 file must produce exactly 1 link", 1, emitted.size)
        assertEquals("URL must match original exactly", "https://cdn.example.com/movie.mp4", emitted[0].url)
        assertFalse("Link name must not contain duplicate badges", emitted[0].name.contains("[720p] [1080p]"))

        // 2. Direct video files sharing the exact same canonical URL must NOT bypass deduplication with fake quality suffixes
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { }
        val streamUrl = "https://cdn.example.com/direct/video.mp4"
        val link720 = createLink(
            source = "VidLink",
            name = "VidLink [720p]",
            url = streamUrl,
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )
        val link1080 = createLink(
            source = "VidLink",
            name = "VidLink [1080p]",
            url = streamUrl,
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )

        val key720 = StreamLinkOptimizer.canonicalStreamKey(link720)
        val key1080 = StreamLinkOptimizer.canonicalStreamKey(link1080)
        assertEquals("Direct video canonical keys must be identical without quality suffix", key720, key1080)

        val res1 = deduplicator.emitDetailed(link1080)
        val res2 = deduplicator.emitDetailed(link720)
        assertEquals("First stream is accepted as NEW", StreamLinkOptimizer.DeduplicationResult.NEW, res1)
        assertEquals("Higher-priority 720p duplicate of identical URL upgrades existing entry", StreamLinkOptimizer.DeduplicationResult.UPGRADED, res2)
        assertEquals("Deduplicator retains exactly 1 stream entry for direct video", 1, deduplicator.getEmittedCount())
    }

    @Test
    fun testR2AuthenticHlsVariantLabelingAndAdaptiveMasterHandling() = runBlocking {
        // 1. Master manifest with only genuine 1080p and 480p variants must NEVER synthesize a fake 720p companion
        val emittedVariants = mutableListOf<ExtractorLink>()
        val var1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/1080.m3u8", Qualities.P1080.value, ExtractorLinkType.M3U8)
        val var480 = createLink("HexaSU", "HexaSU [480p]", "https://hexa.su/480.m3u8", Qualities.P480.value, ExtractorLinkType.M3U8)

        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "HexaSU",
            baseName = "HexaSU Master",
            url = "https://hexa.su/master.m3u8",
            referer = "https://hexa.su/",
            streamType = ExtractorLinkType.M3U8,
            generatedLinks = listOf(var1080, var480),
            callback = { emittedVariants.add(it) }
        )

        assertEquals("Must emit exactly the 2 genuine variants parsed from manifest", 2, emittedVariants.size)
        assertEquals(Qualities.P1080.value, emittedVariants[0].quality)
        assertEquals(Qualities.P480.value, emittedVariants[1].quality)
        assertTrue("No synthetic 720p companion may be emitted", emittedVariants.none { it.quality == Qualities.P720.value })

        // 2. Unparsed HLS master playlist URL must be emitted as a single stream tagged [Auto] with Qualities.Unknown.value
        val emittedMaster = mutableListOf<ExtractorLink>()
        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = "AutoEmbed",
            baseName = "AutoEmbed HLS",
            url = "https://autoembed.cc/master.m3u8",
            referer = "https://autoembed.cc/",
            streamType = ExtractorLinkType.M3U8,
            generatedLinks = null,
            callback = { emittedMaster.add(it) }
        )

        assertEquals("Unparsed master playlist must emit exactly 1 link", 1, emittedMaster.size)
        assertEquals("Quality must be Qualities.Unknown.value", Qualities.Unknown.value, emittedMaster[0].quality)
        assertTrue("Stream name must contain [Auto] badge", emittedMaster[0].name.contains("[Auto]"))
        assertFalse("Stream name must NOT contain fabricated resolution badges", emittedMaster[0].name.contains("1080p") || emittedMaster[0].name.contains("720p"))

        // 3. formatLinkName must add [Auto] badge when quality <= 0 or Unknown on unparsed master/M3U8 stream
        val formatted = StreamLinkOptimizer.formatLinkName(
            currentName = "VidFast Master",
            quality = Qualities.Unknown.value,
            url = "https://vidfast.vc/stream/master.m3u8"
        )
        assertTrue("formatLinkName must inject [Auto] badge for unparsed M3U8", formatted.contains("[Auto]"))
    }

    @Test
    fun testR3StreamPriorityHierarchyWithAuthenticStreams() {
        val link720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val link1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val linkAuto = createLink("VidLink", "VidLink [Auto]", "https://vidlink.pro/master.m3u8", Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
        val link480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)
        val link4K = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/4k.m3u8", Qualities.P2160.value)

        val score720 = StreamLinkOptimizer.getStreamCompositeScore(link720)
        val score1080 = StreamLinkOptimizer.getStreamCompositeScore(link1080)
        val score480 = StreamLinkOptimizer.getStreamCompositeScore(link480)
        val score4K = StreamLinkOptimizer.getStreamCompositeScore(link4K)

        // SOTA Quality Hierarchy: 720p (instant playback) > 1080p (high fidelity) > 480p (fallback SD) > 4K (heavy bandwidth)
        assertTrue("720p score ($score720) must be higher than 1080p score ($score1080)", score720 > score1080)
        assertTrue("1080p score ($score1080) must be higher than 480p score ($score480)", score1080 > score480)
        assertTrue("480p score ($score480) must be higher than 4K score ($score4K)", score480 > score4K)

        // Sorting by STREAM_PRIORITY_COMPARATOR must order streams correctly
        val list = listOf(link4K, link480, link1080, link720)
        val sorted = list.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals("Priority #1 must be 720p", Qualities.P720.value, sorted[0].quality)
        assertEquals("Priority #2 must be 1080p", Qualities.P1080.value, sorted[1].quality)
        assertEquals("Priority #3 must be 480p", Qualities.P480.value, sorted[2].quality)
        assertEquals("Priority #4 must be 4K", Qualities.P2160.value, sorted[3].quality)
    }

    @Test
    fun testSotaTopTierMonotonicityModernProviders() {
        // Strict top-tier SOTA priority ordering: VidLink 100 > YFlix 90 > CineJoy 80 > VidFast 70 > VidEasy 60 > VidSrc 55
        val rVidlink = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidLink", "VidLink", "https://vidlink.pro/v.m3u8"))
        val rYflix = StreamLinkOptimizer.getSourcePriorityRank(createLink("YFlix", "YFlix", "https://yflix.to/v.m3u8"))
        val rCinejoy = StreamLinkOptimizer.getSourcePriorityRank(createLink("CineJoy", "CineJoy", "https://cinejoy.to/v.m3u8"))
        val rVidfast = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidFast", "VidFast", "https://vidfast.vc/v.m3u8"))
        val rVideasy = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidEasy", "VidEasy", "https://player.videasy.to/v.m3u8"))
        val rVidsrc = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidSrc", "VidSrc", "https://vidsrc.xyz/v.m3u8"))

        assertEquals(100, rVidlink)
        assertEquals(90, rYflix)
        assertEquals(80, rCinejoy)
        assertEquals(70, rVidfast)
        assertEquals(60, rVideasy)
        assertEquals(55, rVidsrc)

        assertTrue("VidLink > YFlix", rVidlink > rYflix)
        assertTrue("YFlix > CineJoy", rYflix > rCinejoy)
        assertTrue("CineJoy > VidFast", rCinejoy > rVidfast)
        assertTrue("VidFast > VidEasy", rVidfast > rVideasy)
        assertTrue("VidEasy > VidSrc", rVideasy > rVidsrc)

        // Verify FAST_PROVIDER_BOOST
        assertEquals(100f, FAST_PROVIDER_BOOST["vidlink"])
        assertEquals(90f, FAST_PROVIDER_BOOST["yflix"])
        assertEquals(90f, FAST_PROVIDER_BOOST["YFlix"])
        assertEquals(90f, FAST_PROVIDER_BOOST["moviesflix"])
        assertEquals(80f, FAST_PROVIDER_BOOST["cinejoy"])
        assertEquals(80f, FAST_PROVIDER_BOOST["CineJoy"])
        assertEquals(70f, FAST_PROVIDER_BOOST["vidfast"])
        assertEquals(60f, FAST_PROVIDER_BOOST["VidEasy"])
        assertEquals(55f, FAST_PROVIDER_BOOST["vidsrc"])

        // Verify Latency Tier 1 classification
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.classifyProvider("yflix"))
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.classifyProvider("cinejoy"))
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.classifyProvider("vidlink"))
    }

    @Test
    fun testDualQualityExtremeTiebreakerDominance() {
        // The lowest possible 720p top-tier stream (VidSrc 720p with 0 bitrate and no badges = 10,550.0f)
        // must strictly outscore the highest possible 1080p stream (VidLink 1080p with max bitrate, Atmos, HDR = 9,009.9f)
        val max1080pVidlink = createLink(
            source = "VidLink",
            name = "VidLink [1080p] [50000kbps] [Atmos] [HDR] [10bit]",
            url = "https://vidlink.pro/stream/1080p.m3u8",
            quality = Qualities.P1080.value,
            headers = mapOf("Origin" to "https://vidlink.pro", "Referer" to "https://vidlink.pro/")
        )
        val min720pVidsrc = createLink(
            source = "VidSrc",
            name = "VidSrc [720p]",
            url = "https://vidsrc.xyz/stream/720p.m3u8",
            quality = Qualities.P720.value
        )

        val scoreMax1080 = StreamLinkOptimizer.getStreamCompositeScore(max1080pVidlink)
        val scoreMin720 = StreamLinkOptimizer.getStreamCompositeScore(min720pVidsrc)

        assertTrue(
            "Lowest top-tier 720p (VidSrc: $scoreMin720) must strictly beat highest top-tier 1080p (VidLink max tiebreaker: $scoreMax1080)",
            scoreMin720 > scoreMax1080
        )
        assertTrue("Separation must exceed 1,500 points", (scoreMin720 - scoreMax1080) > 1500f)

        // Test every combination of top-tier providers: 720p always strictly beats 1080p
        val providers = listOf("VidLink", "YFlix", "CineJoy", "VidFast", "VidEasy", "VidSrc")
        for (p720 in providers) {
            val link720 = createLink(p720, "$p720 [720p]", "https://example.com/720.m3u8", Qualities.P720.value)
            val score720 = StreamLinkOptimizer.getStreamCompositeScore(link720)

            for (p1080 in providers) {
                val link1080 = createLink(
                    source = p1080,
                    name = "$p1080 [1080p] [50000kbps] [Atmos]",
                    url = "https://example.com/1080.m3u8",
                    quality = Qualities.P1080.value
                )
                val score1080 = StreamLinkOptimizer.getStreamCompositeScore(link1080)
                assertTrue(
                    "$p720 720p ($score720) must strictly exceed $p1080 1080p ($score1080)",
                    score720 > score1080
                )
            }
        }
    }

    @Test
    fun testSettingsMigrationCleanInstallV9() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        val disabled = getOrInitializeDisabledProviders(mockPrefs)

        val allProviders = buildProviders()
        val active = allProviders.map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals("Clean install v9 must activate exactly 3 top-tier providers", 3, active.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, active)
        assertTrue("vidlink must be active", active.contains("vidlink"))
        assertTrue("vidcore must be active", active.contains("vidcore"))
        assertTrue("vidup must be active", active.contains("vidup"))
        assertFalse("cinejoy must not be active", active.contains("cinejoy"))

        // Dead registered providers must be disabled
        assertTrue("HexaSU must be disabled", disabled.contains("HexaSU"))
        assertTrue("autoembed must be disabled", disabled.contains("autoembed"))
        assertTrue("vidfast must be disabled", disabled.contains("vidfast"))
        assertTrue("VidEasy must be disabled", disabled.contains("VidEasy"))
        assertTrue("yflix must be disabled", disabled.contains("yflix"))
        assertTrue("cinejoy must be disabled", disabled.contains("cinejoy"))

        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testSettingsMigrationUpgradeFromV8PreservesUserOverrides() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        // Simulate legacy user who had:
        // 1. Explicitly disabled "vidlink" (a top-tier provider override)
        // 2. Explicitly enabled "moviebox" (a secondary provider override)
        // 3. vidfast and VidEasy were active under v7
        val oldDisabled = (getDefaultDisabledProviderIds() + "vidlink") - "moviebox"
        mockPrefs.edit()
            .putStringSet("disabled_providers", oldDisabled)
            .putBoolean("streamplay_top_tier_v8_initialized", true)
            .apply()

        val finalDisabled = getOrInitializeDisabledProviders(mockPrefs)

        // Dead providers must be purged and added to disabled_providers
        assertTrue("HexaSU must be disabled in v9", finalDisabled.contains("HexaSU"))
        assertTrue("autoembed must be disabled in v9", finalDisabled.contains("autoembed"))
        assertTrue("superstream must be disabled in v9", finalDisabled.contains("superstream"))
        assertTrue("vaplayer must be disabled in v9", finalDisabled.contains("vaplayer"))
        assertTrue("vidfast must be disabled in v9", finalDisabled.contains("vidfast"))
        assertTrue("VidEasy must be disabled in v9", finalDisabled.contains("VidEasy"))
        assertTrue("yflix must be disabled in v9", finalDisabled.contains("yflix"))
        assertTrue("vidsrc must be disabled in v9", finalDisabled.contains("vidsrc"))
        assertTrue("cinejoy must be disabled in v9", finalDisabled.contains("cinejoy"))

        // Newly promoted SOTA providers must be enabled
        assertFalse("vidcore must be enabled in v9", finalDisabled.contains("vidcore"))
        assertFalse("vidup must be enabled in v9", finalDisabled.contains("vidup"))

        // User custom overrides MUST be strictly preserved
        assertTrue("User's custom disable of vidlink must be preserved", finalDisabled.contains("vidlink"))
        assertFalse("User's custom enable of moviebox must be preserved", finalDisabled.contains("moviebox"))
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testDeadProvidersHexaAndAutoembedGracefulZeroLinks() = runBlocking {
        val hexaLinks = mutableListOf<ExtractorLink>()
        StreamPlayExtractor.invokeHexa(
            tmdbId = 999999,
            season = null,
            episode = null,
            subtitleCallback = {},
            callback = { hexaLinks.add(it) }
        )
        assertEquals("Dead HexaSU must complete cleanly with 0 links", 0, hexaLinks.size)

        val autoembedLinks = mutableListOf<ExtractorLink>()
        StreamPlayExtractor.invokeAutoembed(
            tmdbId = 999999,
            season = null,
            episode = null,
            subtitleCallback = {},
            callback = { autoembedLinks.add(it) }
        )
        assertEquals("Dead AutoEmbed must complete cleanly with 0 links", 0, autoembedLinks.size)
    }

    @Test
    fun testSotaExtractorsYFlixAndCineJoySafeExecution() = runBlocking {
        val yflixLinks = mutableListOf<ExtractorLink>()
        StreamPlayExtractor.invokeYFlix(
            title = "Test Invalid Title 12345",
            tmdbId = 999999,
            imdbId = "tt9999999",
            year = 2026,
            season = null,
            episode = null,
            subtitleCallback = {},
            callback = { yflixLinks.add(it) }
        )
        // Safe execution without exceptions
        assertTrue("invokeYFlix completes cleanly", true)

        val cinejoyLinks = mutableListOf<ExtractorLink>()
        StreamPlayExtractor.invokeCineJoy(
            title = "Test Invalid Title 12345",
            tmdbId = 999999,
            imdbId = "tt9999999",
            year = 2026,
            season = null,
            episode = null,
            subtitleCallback = {},
            callback = { cinejoyLinks.add(it) }
        )
        // Safe execution without exceptions
        assertTrue("invokeCineJoy completes cleanly", true)
    }

    @Test
    fun testPriorityStreamDispatcherModernSotaFullPipeline() = runBlocking {
        val emittedLinks = mutableListOf<ExtractorLink>()
        val inFlightRanks = mutableSetOf(90, 80, 70)
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emittedLinks.add(it) },
            scope = this,
            stageWindowMs = 50L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 1500L,
            activeTopRanks = setOf(90, 80, 70),
            isRankInFlight = { rank -> inFlightRanks.contains(rank) }
        )

        val vidfast1080 = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/1080.m3u8", Qualities.P1080.value)
        val vidfast720 = createLink("VidFast", "VidFast [720p]", "https://vidfast.vc/720.m3u8", Qualities.P720.value)
        val cinejoy720 = createLink("CineJoy", "CineJoy [720p]", "https://cinejoy.to/720.m3u8", Qualities.P720.value)
        val yflix720 = createLink("YFlix", "YFlix [720p]", "https://yflix.to/720.m3u8", Qualities.P720.value)

        // Subtitles arrive
        dispatcher.onSubtitleReceived()

        // 1. VidFast emits 1080p at t=0, but higher-priority 720p sources (ranks 90, 80) are in flight
        dispatcher.onLinkAccepted(vidfast1080)
        delay(80L)
        // Held in pending1080Links because higher-priority sources are in flight
        assertTrue("VidFast 1080p held while higher-ranked 720p sources are in-flight", emittedLinks.isEmpty())

        // 2. VidFast 720p arrives at t=100ms
        dispatcher.onLinkAccepted(vidfast720)
        inFlightRanks.remove(70)
        dispatcher.markRankCompleted(70)

        // 3. CineJoy 720p arrives at t=120ms
        dispatcher.onLinkAccepted(cinejoy720)
        inFlightRanks.remove(80)
        dispatcher.markRankCompleted(80)

        // 4. YFlix 720p arrives at t=140ms
        dispatcher.onLinkAccepted(yflix720)
        inFlightRanks.remove(90)
        dispatcher.markRankCompleted(90)

        // All 720p streams emit in strict rank order ahead of 1080p, followed immediately by 1080p
        assertEquals("All 4 streams must emit in strict rank and quality order", 4, emittedLinks.size)
        assertEquals("Priority #1 must be YFlix 720p", yflix720, emittedLinks[0])
        assertEquals("Priority #2 must be CineJoy 720p", cinejoy720, emittedLinks[1])
        assertEquals("Priority #3 must be VidFast 720p", vidfast720, emittedLinks[2])
        assertEquals("Priority #4 must be VidFast 1080p", vidfast1080, emittedLinks[3])

        dispatcher.flush()
    }
}



