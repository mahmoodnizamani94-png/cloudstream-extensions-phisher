package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SotaEngineEnhancementTest {

    private fun createLink(
        source: String = "TestSrc",
        name: String = "TestLink",
        url: String,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        referer: String = "",
        quality: Int = Qualities.Unknown.value,
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

    // ==================== 1. Visual Badging Engine Tests ====================

    @Test
    fun testDolbyVisionAndHdrBadges() {
        val dvLink = createLink(name = "Movie 2160p DoVi HEVC", url = "https://cdn.example.com/movie.mkv")
        val optDv = StreamLinkOptimizer.optimize(dvLink)
        assertTrue("Contains [DV]", optDv.name.contains("[DV]"))
        assertTrue("Contains [HEVC]", optDv.name.contains("[HEVC]"))

        val hdrLink = createLink(name = "Movie 2160p HDR10 HEVC", url = "https://cdn.example.com/movie.mkv")
        val optHdr = StreamLinkOptimizer.optimize(hdrLink)
        assertTrue("Contains [HDR]", optHdr.name.contains("[HDR]"))

        val hdr10PlusLink = createLink(name = "Show HDR10+ WEB-DL", url = "https://cdn.example.com/show.mp4")
        val optHdr10Plus = StreamLinkOptimizer.optimize(hdr10PlusLink)
        assertTrue("Contains [HDR10+]", optHdr10Plus.name.contains("[HDR10+]"))
        assertTrue("Contains [WEB-DL]", optHdr10Plus.name.contains("[WEB-DL]"))

        val tenBitLink = createLink(name = "Anime 1080p 10-bit AV1", url = "https://cdn.example.com/anime.mkv")
        val optTenBit = StreamLinkOptimizer.optimize(tenBitLink)
        assertTrue("Contains [10-bit]", optTenBit.name.contains("[10-bit]"))
        assertTrue("Contains [AV1]", optTenBit.name.contains("[AV1]"))
    }

    @Test
    fun testSourceTypeAndAudioBadges() {
        val remuxLink = createLink(name = "Movie REMUX TrueHD Atmos", url = "https://cdn.example.com/remux.mkv")
        val optRemux = StreamLinkOptimizer.optimize(remuxLink)
        assertTrue("Contains [REMUX]", optRemux.name.contains("[REMUX]"))
        assertTrue("Contains [Atmos]", optRemux.name.contains("[Atmos]"))

        val blurayLink = createLink(name = "Movie BluRay 5.1 x264", url = "https://cdn.example.com/bluray.mkv")
        val optBluray = StreamLinkOptimizer.optimize(blurayLink)
        assertTrue("Contains [BluRay]", optBluray.name.contains("[BluRay]"))
        assertTrue("Contains [5.1]", optBluray.name.contains("[5.1]"))
        assertTrue("Contains [AVC]", optBluray.name.contains("[AVC]"))
    }

    @Test
    fun testBadgeIdempotency() {
        val link = createLink(name = "Video 1080p [DV] [HDR] [HEVC] [REMUX] [Atmos]", url = "https://cdn.example.com/test.mkv")
        val optOnce = StreamLinkOptimizer.optimize(link)
        val optTwice = StreamLinkOptimizer.optimize(optOnce)

        assertEquals(optOnce.name, optTwice.name)
        assertFalse("No duplicate [DV]", optTwice.name.contains("[DV] [DV]"))
        assertFalse("No duplicate [HDR]", optTwice.name.contains("[HDR] [HDR]"))
        assertFalse("No duplicate [REMUX]", optTwice.name.contains("[REMUX] [REMUX]"))
    }

    // ==================== 2. CDN Anti-Throttling Header Injection ====================

    @Test
    fun testMixDropAntiThrottling() {
        val link = createLink(url = "https://mixdrop.ag/e/abc123xyz")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://mixdrop.ag/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://mixdrop.ag", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testFilemoonAntiThrottling() {
        val link = createLink(url = "https://filemoon.sx/e/xyz789")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://filemoon.sx/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://filemoon.sx", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testVidhideAntiThrottling() {
        val link = createLink(url = "https://vidhide.com/v/sample123")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://vidhide.com/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://vidhide.com", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testStreamWishAntiThrottling() {
        val link = createLink(url = "https://streamwish.to/e/stream123")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://streamwish.to/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://streamwish.to", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testVoeAntiThrottling() {
        val link = createLink(url = "https://voe.sx/e/voestream")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://voe.sx/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://voe.sx", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testMp4uploadAntiThrottling() {
        val link = createLink(url = "https://mp4upload.com/embed-test123.html")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://www.mp4upload.com/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://www.mp4upload.com", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    // ==================== 3. Canonical Stream Key Transient Stripping ====================

    @Test
    fun testTransientQueryParamStripping() {
        val url1 = "https://edge-cdn.example.com/stream/master.m3u8?wsiphost=ip1&auth_token=tok1&dl=1"
        val url2 = "https://edge-cdn.example.com/stream/master.m3u8?wsiphost=ip2&auth_token=tok2&access_token=tok3&stream_id=99"

        val link1 = createLink(url = url1, type = ExtractorLinkType.M3U8)
        val link2 = createLink(url = url2, type = ExtractorLinkType.M3U8)

        val key1 = StreamLinkOptimizer.canonicalStreamKey(link1)
        val key2 = StreamLinkOptimizer.canonicalStreamKey(link2)

        assertEquals("Transient tokens stripped to produce identical key", key1, key2)
    }

    // ==================== 4. Link Comparison & Upgrade Evaluation ====================

    @Test
    fun testIsBetterThanSourceAndHdrComparison() {
        val remuxLink = createLink(name = "Movie [REMUX] [1080p]", url = "https://cdn.example.com/remux.mkv", quality = Qualities.P1080.value)
        val webDlLink = createLink(name = "Movie [WEB-DL] [1080p]", url = "https://cdn.example.com/webdl.mkv", quality = Qualities.P1080.value)

        assertTrue("REMUX beats WEB-DL at same resolution", StreamLinkOptimizer.isBetterThan(remuxLink, webDlLink))
        assertFalse("WEB-DL does not beat REMUX", StreamLinkOptimizer.isBetterThan(webDlLink, remuxLink))

        val dvLink = createLink(name = "Movie [DV] [HDR] [1080p]", url = "https://cdn.example.com/dv.mkv", quality = Qualities.P1080.value)
        val sdrLink = createLink(name = "Movie [1080p]", url = "https://cdn.example.com/sdr.mkv", quality = Qualities.P1080.value)

        assertTrue("DV beats SDR at same resolution", StreamLinkOptimizer.isBetterThan(dvLink, sdrLink))
    }

    // ==================== 5. SpeculativePipeliner Adaptive Escalation Tests ====================

    @Test
    fun testAdaptiveTierEscalationWhenTier0Empty() = runBlocking {
        val config = EarlySatisfactionConfig(
            tier1DelayMs = 400L,
            adaptiveTierEscalation = true
        )
        val controller = EarlySatisfactionController(config)

        val tier1ExecutedAt = AtomicInteger(-1)
        val startTime = System.currentTimeMillis()

        val tasks = listOf(
            PipelinedTask("Provider1", LatencyTier.TIER_1) {
                tier1ExecutedAt.set((System.currentTimeMillis() - startTime).toInt())
            }
        )

        SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        val execTime = tier1ExecutedAt.get()
        assertTrue("Tier 1 started immediately (delay skipped): execTime was ${execTime}ms", execTime < 250)
    }

    @Test
    fun testAdaptiveTierEscalationOnEarlyTiersFailure() = runBlocking {
        val config = EarlySatisfactionConfig(
            tier1DelayMs = 50L,
            tier2DelayMs = 2000L,
            adaptiveTierEscalation = true
        )
        val controller = EarlySatisfactionController(config)

        val tier2ExecutedAt = AtomicInteger(-1)
        val startTime = System.currentTimeMillis()

        val tasks = listOf(
            PipelinedTask("Tier1Provider", LatencyTier.TIER_1) {
                delay(10)
            },
            PipelinedTask("Tier2Provider", LatencyTier.TIER_2) {
                tier2ExecutedAt.set((System.currentTimeMillis() - startTime).toInt())
            }
        )

        SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        val execTime = tier2ExecutedAt.get()
        assertTrue("Tier 2 escalated quickly upon Tier 1 completion with 0 links: took ${execTime}ms", execTime < 1000)
    }

    @Test
    fun testSoftGracePeriodAfterFirstLink() = runBlocking {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 5,
            minQualityStreams = 5,
            softGracePeriodAfterFirstLinkMs = 150L,
            checkIntervalMs = 10L
        )
        val controller = EarlySatisfactionController(config)

        val linkEmitted = AtomicBoolean(false)
        val startTime = System.currentTimeMillis()

        val tasks = listOf(
            PipelinedTask("FastProvider", LatencyTier.TIER_0) {
                delay(20)
                controller.onLinkEmitted(createLink(url = "https://cdn.example.com/1080p.mp4", quality = Qualities.P1080.value))
                linkEmitted.set(true)
            },
            PipelinedTask("SlowLingeringProvider", LatencyTier.TIER_3) {
                delay(5000)
            }
        )

        SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        val totalDuration = System.currentTimeMillis() - startTime
        assertTrue("Link was emitted", linkEmitted.get())
        assertTrue("Soft grace period terminated pipeline quickly: took ${totalDuration}ms", totalDuration < 1500)
    }

    // ==================== 6. Adversarial Edge Case & Bug Fix Verification ====================

    @Test
    fun testAudioBitrate64kDoesNotFalsePositiveAs4k() {
        val config = EarlySatisfactionConfig(
            minQualityStreams = 1,
            qualityThreshold = Qualities.P1080.value
        )
        val controller = EarlySatisfactionController(config)

        val lowQualityLink = createLink(
            name = "Indie Movie 480p AAC 64kbps",
            url = "https://cdn.example.com/video.mp4",
            quality = Qualities.P480.value
        )

        assertFalse(
            "64kbps audio must not trigger false positive 4K match",
            controller.isHighQualityVerifiedStream(lowQualityLink)
        )

        val link14k = createLink(
            name = "Low Bandwidth Stream 14kbps",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P360.value
        )
        assertFalse("14kbps must not trigger 4K", controller.isHighQualityVerifiedStream(link14k))

        val genuine4kLink = createLink(
            name = "Movie 4K UHD Remux",
            url = "https://cdn.example.com/4k.mkv",
            quality = Qualities.P2160.value
        )
        assertTrue("Genuine 4K must be recognized", controller.isHighQualityVerifiedStream(genuine4kLink))
    }

    @Test
    fun testStreamUpgradeDoesNotInflateUniqueLinksCount() {
        val uniqueCount = AtomicInteger(0)
        val upgradeCount = AtomicInteger(0)

        val deduplicator = StreamLinkOptimizer.StreamDeduplicator(
            upstreamCallback = { uniqueCount.incrementAndGet() },
            onUpgradeCallback = { upgradeCount.incrementAndGet() }
        )

        val initial480p = createLink(
            name = "Movie 480p",
            url = "https://cdn.example.com/movie.mp4",
            quality = Qualities.P480.value
        )
        val upgraded1080p = createLink(
            name = "Movie 1080p",
            url = "https://cdn.example.com/movie.mp4",
            quality = Qualities.P1080.value
        )

        val res1 = deduplicator.emitDetailed(initial480p)
        assertEquals(StreamLinkOptimizer.DeduplicationResult.NEW, res1)
        assertEquals(1, uniqueCount.get())
        assertEquals(0, upgradeCount.get())

        val res2 = deduplicator.emitDetailed(upgraded1080p)
        assertEquals(StreamLinkOptimizer.DeduplicationResult.UPGRADED, res2)
        assertEquals("Unique stream count must remain 1 after upgrade", 1, uniqueCount.get())
        assertEquals("Upgrade callback must be triggered exactly once", 1, upgradeCount.get())
    }

    @Test
    fun testResolutionStrictlyPrecedesBitrateInIsBetterThan() {
        val link1080p = createLink(
            name = "Movie [1080p] [2500 kbps]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P1080.value
        )
        val link720pHighBitrate = createLink(
            name = "Movie [720p] [6000 kbps]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value
        )

        assertTrue("1080p must beat 720p despite 720p claiming higher bitrate", StreamLinkOptimizer.isBetterThan(link1080p, link720pHighBitrate))
        assertFalse("720p must not beat 1080p", StreamLinkOptimizer.isBetterThan(link720pHighBitrate, link1080p))
    }

    @Test
    fun testAdditionalAntiThrottlingHosts() {
        val faststream = StreamLinkOptimizer.optimize(createLink(url = "https://faststream.org/embed/123"))
        assertEquals("https://faststream.org/", faststream.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://faststream.org", faststream.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val streamruby = StreamLinkOptimizer.optimize(createLink(url = "https://streamruby.com/e/456"))
        assertEquals("https://streamruby.com/", streamruby.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://streamruby.com", streamruby.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val embedrise = StreamLinkOptimizer.optimize(createLink(url = "https://embedrise.org/v/789"))
        assertEquals("https://embedrise.org/", embedrise.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://embedrise.org", embedrise.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val ridoo = StreamLinkOptimizer.optimize(createLink(url = "https://ridoo.net/e/abc"))
        assertEquals("https://ridoo.net/", ridoo.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://ridoo.net", ridoo.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val asnwish = StreamLinkOptimizer.optimize(createLink(url = "https://asnwish.com/e/def"))
        assertEquals("https://asnwish.com/", asnwish.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://asnwish.com", asnwish.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val luluvdo = StreamLinkOptimizer.optimize(createLink(url = "https://luluvdo.com/e/ghi"))
        assertEquals("https://luluvdo.com/", luluvdo.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://luluvdo.com", luluvdo.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val streamvid = StreamLinkOptimizer.optimize(createLink(url = "https://streamvid.net/player/jkl"))
        assertEquals("https://streamvid.net/", streamvid.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://streamvid.net", streamvid.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val dropload = StreamLinkOptimizer.optimize(createLink(url = "https://dropload.io/d/mno"))
        assertEquals("https://dropload.io/", dropload.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://dropload.io", dropload.headers[StreamLinkOptimizer.HEADER_ORIGIN])

        val filelions = StreamLinkOptimizer.optimize(createLink(url = "https://filelions.to/v/pqr"))
        assertEquals("https://filelions.to/", filelions.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://filelions.to", filelions.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testAdaptiveTierEscalationWhenEarlierTiersEmptySkipsDelay() = runBlocking {
        val config = EarlySatisfactionConfig(
            tier2DelayMs = 1500L,
            adaptiveTierEscalation = true
        )
        val controller = EarlySatisfactionController(config)

        val tier2ExecutedAt = AtomicInteger(-1)
        val startTime = System.currentTimeMillis()

        // Only Tier 2 tasks, Tier 0 and Tier 1 have zero tasks
        val tasks = listOf(
            PipelinedTask("Tier2OnlyProvider", LatencyTier.TIER_2) {
                tier2ExecutedAt.set((System.currentTimeMillis() - startTime).toInt())
            }
        )

        SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        val execTime = tier2ExecutedAt.get()
        assertTrue("Tier 2 must start immediately (< 250ms) when earlier tiers are empty, took ${execTime}ms", execTime in 0..250)
    }
}
