package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualQualityAndSotaHierarchyTest {

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
        // Users must get 720 and 1080 as highest prioritized, then below 720, then above 1080
        val score1080 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1080.value)
        val score720 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P720.value)
        val score576 = StreamLinkOptimizer.getQualityPriorityScore(576)
        val score480 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P480.value)
        val score360 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P360.value)
        val score1440 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1440.value)
        val score2160 = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P2160.value)
        val scoreUnknown = StreamLinkOptimizer.getQualityPriorityScore(Qualities.Unknown.value)

        assertEquals(10000, score1080)
        assertEquals(9000, score720)
        assertEquals(5576, score576)
        assertEquals(5480, score480)
        assertEquals(5360, score360)

        // Strict monotonicity checks
        assertTrue("1080p must beat 720p", score1080 > score720)
        assertTrue("720p must beat 576p (below 720)", score720 > score576)
        assertTrue("576p must beat 480p", score576 > score480)
        assertTrue("480p must beat 360p", score480 > score360)
        assertTrue("360p (below 720) must beat 1440p (above 1080)", score360 > score1440)
        assertTrue("1440p must beat 2160p", score1440 > score2160)
        assertTrue("2160p must beat Unknown", score2160 > scoreUnknown)

        // Prioritized checker
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(Qualities.P1080.value))
        assertTrue(StreamLinkOptimizer.isQualityPrioritized(Qualities.P720.value))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(Qualities.P2160.value))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(Qualities.P480.value))
        assertFalse(StreamLinkOptimizer.isQualityPrioritized(Qualities.Unknown.value))
    }

    @Test
    fun testSourcePriorityRankStrictMonotonicity() {
        // Priority order: VidLink (100) > HexaSU (90) > AutoEmbed (80) > VidFast (70) > VidEasy (60) > VidSrc (55)
        val rVidlink = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidLink", "VidLink", "https://vidlink.pro/v.m3u8"))
        val rHexa = StreamLinkOptimizer.getSourcePriorityRank(createLink("HexaSU", "HexaSU", "https://hexa.su/v.m3u8"))
        val rAutoembed = StreamLinkOptimizer.getSourcePriorityRank(createLink("AutoEmbed", "AutoEmbed", "https://autoembed.cc/v.m3u8"))
        val rVidfast = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidFast", "VidFast", "https://vidfast.vc/v.m3u8"))
        val rVideasy = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidEasy", "VidEasy", "https://player.videasy.to/v.m3u8"))
        val rVidsrc = StreamLinkOptimizer.getSourcePriorityRank(createLink("VidSrc", "VidSrc", "https://vidsrc.xyz/v.m3u8"))

        assertEquals(100, rVidlink)
        assertEquals(90, rHexa)
        assertEquals(80, rAutoembed)
        assertEquals(70, rVidfast)
        assertEquals(60, rVideasy)
        assertEquals(55, rVidsrc)

        assertTrue(rVidlink > rHexa)
        assertTrue(rHexa > rAutoembed)
        assertTrue(rAutoembed > rVidfast)
        assertTrue(rVidfast > rVideasy)
        assertTrue(rVideasy > rVidsrc)
    }

    @Test
    fun testCompositeStreamScoreOrdering() {
        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val vidlink720 = createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value)
        val vidlink480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)
        val vidlink4k = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/4k.m3u8", Qualities.P2160.value)

        val s1080 = StreamLinkOptimizer.getStreamCompositeScore(vidlink1080)
        val s720 = StreamLinkOptimizer.getStreamCompositeScore(vidlink720)
        val s480 = StreamLinkOptimizer.getStreamCompositeScore(vidlink480)
        val s4k = StreamLinkOptimizer.getStreamCompositeScore(vidlink4k)

        assertTrue("VidLink 1080p score ($s1080) > 720p ($s720)", s1080 > s720)
        assertTrue("VidLink 720p score ($s720) > 480p ($s480)", s720 > s480)
        assertTrue("VidLink 480p score ($s480) > 4K ($s4k)", s480 > s4k)

        // Comparator check
        val list = listOf(vidlink4k, vidlink480, vidlink720, vidlink1080)
        val sorted = list.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals(vidlink1080, sorted[0])
        assertEquals(vidlink720, sorted[1])
        assertEquals(vidlink480, sorted[2])
        assertEquals(vidlink4k, sorted[3])
    }

    @Test
    fun testCrossSourceQualityFirstPrioritization() {
        // Lower-ranked source at 1080p or 720p must be prioritized over higher-ranked source at 480p or 4K
        val hexa1080 = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/1080.m3u8", Qualities.P1080.value)
        val vidlink480 = createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value)
        val vidlink4k = createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/4k.m3u8", Qualities.P2160.value)
        val autoembed720 = createLink("AutoEmbed", "AutoEmbed [720p]", "https://autoembed.cc/720.m3u8", Qualities.P720.value)

        val sHexa1080 = StreamLinkOptimizer.getStreamCompositeScore(hexa1080)
        val sAuto720 = StreamLinkOptimizer.getStreamCompositeScore(autoembed720)
        val sVidlink480 = StreamLinkOptimizer.getStreamCompositeScore(vidlink480)
        val sVidlink4k = StreamLinkOptimizer.getStreamCompositeScore(vidlink4k)

        assertTrue("HexaSU 1080p ($sHexa1080) must beat VidLink 480p ($sVidlink480)", sHexa1080 > sVidlink480)
        assertTrue("AutoEmbed 720p ($sAuto720) must beat VidLink 480p ($sVidlink480)", sAuto720 > sVidlink480)
        assertTrue("HexaSU 1080p ($sHexa1080) must beat VidLink 4K ($sVidlink4k)", sHexa1080 > sVidlink4k)
        assertTrue("AutoEmbed 720p ($sAuto720) must beat VidLink 4K ($sVidlink4k)", sAuto720 > sVidlink4k)
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
    fun testDirectMp4FileQualityUpgradePreserved() {
        val emittedList = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        val mp4_720 = createLink("Scraper", "Direct [720p]", "https://direct.cdn.com/movie.mp4", Qualities.P720.value, type = ExtractorLinkType.VIDEO)
        val mp4_1080 = createLink("Scraper", "Direct [1080p]", "https://direct.cdn.com/movie.mp4", Qualities.P1080.value, type = ExtractorLinkType.VIDEO)

        assertEquals(StreamLinkOptimizer.DeduplicationResult.NEW, deduplicator.emitDetailed(mp4_720))
        assertEquals("For single MP4 file, 1080p upgrades 720p", StreamLinkOptimizer.DeduplicationResult.UPGRADED, deduplicator.emitDetailed(mp4_1080))
        assertEquals(1, deduplicator.getEmittedCount())
        assertEquals(Qualities.P1080.value, deduplicator.getEmittedLinks().first().quality)
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
}
