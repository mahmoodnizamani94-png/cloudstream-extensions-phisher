package com.phisher98

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone 2 Test Suite: Universal Stream Link Optimization Engine (R3)
 *
 * Comprehensive validation of:
 * 1. Anti-throttling header injection & host Referer/Origin dispatch
 * 2. Direct unthrottled CDN rewrites (PixelDrain, Gofile, StreamTape)
 * 3. Container & manifest auto-detection (M3U8, DASH, VIDEO, Azure/AWS patterns)
 * 4. Bitrate calculation & quality inference
 * 5. Audio track normalization & badge idempotency
 * 6. Canonical stream key generation (stripping 32 transient tokens, CDN host clusters, magnet infohash)
 * 7. StreamDeduplicator atomic lock-free upgrades and thread safety
 */
class StreamLinkOptimizerTest {

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

    // ==================== 1. Header Injection Tests ====================

    @Test
    fun testHeaderInjectionIdentityEncoding() {
        val link = createLink(url = "https://cdn.example.com/stream.mp4")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("identity", opt.headers[StreamLinkOptimizer.HEADER_ACCEPT_ENCODING])
    }

    @Test
    fun testHeaderInjectionDefaultUserAgent() {
        val link = createLink(url = "https://cdn.example.com/stream.mp4")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(USER_AGENT, opt.headers[StreamLinkOptimizer.HEADER_USER_AGENT])
    }

    @Test
    fun testHeaderInjectionPreservesCustomUserAgent() {
        val customUa = "CustomBot/1.0 (Testing)"
        val link = createLink(
            url = "https://cdn.example.com/stream.mp4",
            headers = mapOf("User-Agent" to customUa)
        )
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(customUa, opt.headers[StreamLinkOptimizer.HEADER_USER_AGENT])
    }

    @Test
    fun testHeaderInjectionKeepAliveConnection() {
        val link = createLink(url = "https://cdn.example.com/stream.mp4")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("keep-alive", opt.headers[StreamLinkOptimizer.HEADER_CONNECTION])
    }

    @Test
    fun testHeaderInjectionStripsStaticRangeHeader() {
        val link = createLink(
            url = "https://cdn.example.com/stream.mp4",
            headers = mapOf("Range" to "bytes=0-", "Accept" to "*/*")
        )
        val opt = StreamLinkOptimizer.optimize(link)
        assertFalse("Static Range header must be stripped", opt.headers.containsKey("Range"))
        assertFalse("Static Range case-insensitive check", opt.headers.keys.any { it.equals("range", ignoreCase = true) })
    }

    @Test
    fun testHeaderInjectionHostRefererAndOriginSpoofing() {
        val link = createLink(url = "https://media.stream.com/v.m3u8", referer = "")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://media.stream.com", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://media.stream.com", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testHeaderInjectionCustomRefererPropagatedToOrigin() {
        val link = createLink(
            url = "https://cdn.example.com/v.mp4",
            referer = "https://embed.mysite.org/play"
        )
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://embed.mysite.org/play", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("https://embed.mysite.org", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testHeaderInjectionSecFetchHeaders() {
        val link = createLink(url = "https://video-edge.cdn.net/stream.mp4")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("video", opt.headers[StreamLinkOptimizer.HEADER_SEC_FETCH_DEST])
        assertEquals("no-cors", opt.headers[StreamLinkOptimizer.HEADER_SEC_FETCH_MODE])
        assertEquals("cross-site", opt.headers[StreamLinkOptimizer.HEADER_SEC_FETCH_SITE])
    }

    // ==================== 2. CDN Rewriting Tests ====================

    @Test
    fun testPixelDrainViewUrlRewriting() {
        val link = createLink(url = "https://pixeldrain.com/u/abc12345")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://pixeldrain.com/api/file/abc12345?download", opt.url)
    }

    @Test
    fun testPixelDrainFileEndpointEnsuresDownloadParam() {
        val link = createLink(url = "https://pixeldrain.com/api/file/abc12345")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://pixeldrain.com/api/file/abc12345?download", opt.url)
    }

    @Test
    fun testPixelDrainStripsRefererAndOrigin() {
        val link = createLink(
            url = "https://pixeldrain.com/u/abc12345",
            referer = "https://thirdparty.com",
            headers = mapOf("Referer" to "https://thirdparty.com", "Origin" to "https://thirdparty.com")
        )
        val opt = StreamLinkOptimizer.optimize(link)
        assertFalse("PixelDrain headers should not have third-party referer", opt.headers.containsKey("Referer"))
        assertFalse("PixelDrain headers should not have third-party origin", opt.headers.containsKey("Origin"))
        assertEquals("PixelDrain effective referer must be empty", "", opt.referer)
    }

    @Test
    fun testGofileUrlAndHeaderRewriting() {
        val link = createLink(url = "https://srv-store5.gofile.io/download/direct/abc/movie.mp4")
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals("https://gofile.io/", opt.headers["Referer"])
        assertEquals("https://gofile.io", opt.headers["Origin"])
        assertEquals("https://gofile.io/", opt.referer)
    }

    @Test
    fun testStreamTapeRedirectorUnthrottling() {
        val link = createLink(url = "https://streamtape.com/get_video?id=xyz&expires=123&ip=1.1.1.1")
        val opt = StreamLinkOptimizer.optimize(link)
        assertTrue("StreamTape URL must have stream=1 parameter", opt.url.contains("&stream=1") || opt.url.contains("?stream=1"))
        assertEquals("https://streamtape.com/", opt.headers["Referer"])
        assertEquals("https://streamtape.com", opt.headers["Origin"])
    }

    // ==================== 3. Manifest Detection Tests ====================

    @Test
    fun testManifestDetectionHlsExtension() {
        val link = createLink(url = "https://cdn.com/playlist.m3u8", type = ExtractorLinkType.VIDEO)
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(ExtractorLinkType.M3U8, opt.type)
        assertTrue(opt.isM3u8)
    }

    @Test
    fun testManifestDetectionHlsQueryParam() {
        val link = createLink(url = "https://cdn.com/stream?format=m3u8", type = ExtractorLinkType.VIDEO)
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(ExtractorLinkType.M3U8, opt.type)
    }

    @Test
    fun testManifestDetectionHlsMasterTxtPattern() {
        val link = createLink(url = "https://cdn.com/hls/master.txt", type = ExtractorLinkType.VIDEO)
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(ExtractorLinkType.M3U8, opt.type)
    }

    @Test
    fun testManifestDetectionDashExtension() {
        val link = createLink(url = "https://cdn.com/manifest.mpd", type = ExtractorLinkType.VIDEO)
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(ExtractorLinkType.DASH, opt.type)
    }

    @Test
    fun testManifestDetectionDirectVideoOverridesHlsPath() {
        val link = createLink(url = "https://cdn.com/hls/sample.mp4", type = ExtractorLinkType.M3U8)
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(ExtractorLinkType.VIDEO, opt.type)
    }

    // ==================== 4. Bitrate & Quality Tests ====================

    @Test
    fun testBitrateEstimationFromSizeAndDuration() {
        val sizeBytes = 1_500_000_000L
        val durationSec = 7200.0
        val bitrate = StreamLinkOptimizer.calculateBitrateKbps(sizeBytes, durationSec)
        assertNotNull(bitrate)
        assertEquals(1666L, bitrate)
    }

    @Test
    fun testBitrateEstimationZeroDurationSafe() {
        val bitrate = StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, 0.0)
        assertNull(bitrate)
        val negativeBitrate = StreamLinkOptimizer.calculateBitrateKbps(-100L, 50.0)
        assertNull(negativeBitrate)
    }

    @Test
    fun testQualityClassificationFromText() {
        assertEquals(Qualities.P2160.value, StreamLinkOptimizer.extractQualityFromText("Movie 2024 2160p HDR"))
        assertEquals(Qualities.P2160.value, StreamLinkOptimizer.extractQualityFromText("Movie 4K UHD"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("Movie 1080p FHD"))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Movie 720p HD"))
        assertEquals(Qualities.P480.value, StreamLinkOptimizer.extractQualityFromText("Movie 480p SD"))
    }

    @Test
    fun testQualityClassificationFallsBackToUrl() {
        val link = createLink(
            name = "Server 1",
            url = "https://cdn.com/Avatar.2009.1080p.mp4",
            quality = Qualities.Unknown.value
        )
        val opt = StreamLinkOptimizer.optimize(link)
        assertEquals(Qualities.P1080.value, opt.quality)
    }

    // ==================== 5. Audio Normalization Tests ====================

    @Test
    fun testAudioNormalizationAtmos() {
        val link = createLink(
            name = "Movie.2024.1080p.Atmos.TrueHD.7.1",
            url = "https://cdn.com/stream.mp4"
        )
        val opt = StreamLinkOptimizer.optimize(link)
        assertTrue("Formatted name contains [Atmos]", opt.name.contains("[Atmos]"))
        assertTrue("Formatted name contains [TrueHD]", opt.name.contains("[TrueHD]"))
    }

    @Test
    fun testAudioNormalizationDolbyDigital51() {
        val link1 = createLink(name = "Film.720p.DDP5.1.EAC3", url = "https://cdn.com/1.mp4")
        val link2 = createLink(name = "Film.AC3.5.1", url = "https://cdn.com/2.mp4")
        val opt1 = StreamLinkOptimizer.optimize(link1)
        val opt2 = StreamLinkOptimizer.optimize(link2)
        assertTrue("Formatted name contains [5.1]", opt1.name.contains("[5.1]"))
        assertTrue("Formatted name contains [5.1]", opt2.name.contains("[5.1]"))
    }

    @Test
    fun testAudioNormalizationDualAudio() {
        val link = createLink(name = "Anime.Episode.01.Dual-Audio.1080p", url = "https://cdn.com/ep1.mp4")
        val opt = StreamLinkOptimizer.optimize(link)
        assertTrue("Formatted name contains [Dual Audio]", opt.name.contains("[Dual Audio]"))
    }

    @Test
    fun testAudioNormalizationIdempotency() {
        val name = "[Atmos] [1080p] Movie Title"
        val formatted = StreamLinkOptimizer.formatLinkName(name, Qualities.P1080.value, "https://cdn.com/v.mp4", null, listOf("[Atmos]"))
        val countAtmos = Regex("""\[Atmos\]""", RegexOption.IGNORE_CASE).findAll(formatted).count()
        assertEquals(1, countAtmos)
    }

    // ==================== 6. Canonical Key & Deduplication Tests ====================

    @Test
    fun testCanonicalKeyStripsTransientQueryTokens() {
        val linkA = createLink(url = "https://cdn.example.com/video.mp4?token=abc&expires=123&sig=xyz")
        val linkB = createLink(url = "https://cdn.example.com/video.mp4?token=def&expires=456&sig=uvw")
        val keyA = StreamLinkOptimizer.canonicalStreamKey(linkA)
        val keyB = StreamLinkOptimizer.canonicalStreamKey(linkB)
        assertEquals("Canonical keys must match after stripping transient tokens", keyA, keyB)
    }

    @Test
    fun testCanonicalKeyPreservesContentQueryParams() {
        val linkA = createLink(url = "https://cdn.example.com/video.mp4?quality=1080p&token=abc")
        val linkB = createLink(url = "https://cdn.example.com/video.mp4?quality=720p&token=abc")
        val keyA = StreamLinkOptimizer.canonicalStreamKey(linkA)
        val keyB = StreamLinkOptimizer.canonicalStreamKey(linkB)
        assertNotEquals("Canonical keys must differ when content query params differ", keyA, keyB)
    }

    @Test
    fun testCanonicalKeyNormalizesCdnHostClusterMirrors() {
        val linkA = createLink(url = "https://cdn1.example.com/movie.mp4")
        val linkB = createLink(url = "https://edge2.example.com/movie.mp4")
        val linkC = createLink(url = "https://s3.example.com/movie.mp4")
        val keyA = StreamLinkOptimizer.canonicalStreamKey(linkA)
        val keyB = StreamLinkOptimizer.canonicalStreamKey(linkB)
        val keyC = StreamLinkOptimizer.canonicalStreamKey(linkC)
        assertEquals("cdn-cluster.example.com/movie.mp4", keyA)
        assertEquals(keyA, keyB)
        assertEquals(keyA, keyC)
    }

    @Test
    fun testCanonicalKeyNormalizesMagnetInfoHash() {
        val linkA = createLink(url = "magnet:?xt=urn:btih:ABCDEF123456&dn=ReleaseA&tr=udp://tracker.open.org:1337")
        val linkB = createLink(url = "magnet:?xt=urn:btih:abcdef123456&dn=ReleaseB&tr=http://tracker.other.org")
        val keyA = StreamLinkOptimizer.canonicalStreamKey(linkA)
        val keyB = StreamLinkOptimizer.canonicalStreamKey(linkB)
        assertEquals("magnet:abcdef123456", keyA)
        assertEquals(keyA, keyB)
    }

    @Test
    fun testDeduplicatorRetainsHigherBitrateStream() {
        val emitted = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emitted.add(it) }

        val streamA = createLink(name = "[1080p] [2500 kbps] Stream", url = "https://cdn.example.com/v.mp4")
        val streamB = createLink(name = "[1080p] [5000 kbps] Stream", url = "https://cdn.example.com/v.mp4")

        val emittedA = deduplicator.emit(streamA)
        val emittedB = deduplicator.emit(streamB)

        assertTrue("Stream A emitted first", emittedA)
        assertTrue("Stream B upgrades Stream A", emittedB)
        assertEquals(2, emitted.size)
        assertEquals(streamB, emitted.last())
    }

    @Test
    fun testDeduplicatorDiscardsLowerBitrateDuplicate() {
        val emitted = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emitted.add(it) }

        val streamA = createLink(name = "[1080p] [5000 kbps] Stream", url = "https://cdn.example.com/v.mp4")
        val streamB = createLink(name = "[1080p] [2500 kbps] Stream", url = "https://cdn.example.com/v.mp4")

        val emittedA = deduplicator.emit(streamA)
        val emittedB = deduplicator.emit(streamB)

        assertTrue("Stream A emitted first", emittedA)
        assertFalse("Stream B discarded as lower bitrate", emittedB)
        assertEquals(1, emitted.size)
        assertEquals(streamA, emitted.first())
    }

    @Test
    fun testDeduplicatorUpgradesResolutionQuality() {
        val emitted = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emitted.add(it) }

        val stream720 = createLink(name = "Stream 720p", url = "https://cdn.example.com/v.mp4", quality = Qualities.P720.value)
        val stream1080 = createLink(name = "Stream 1080p", url = "https://cdn.example.com/v.mp4", quality = Qualities.P1080.value)

        assertTrue(deduplicator.emit(stream720))
        assertTrue(deduplicator.emit(stream1080))
        assertEquals(2, emitted.size)
        assertEquals(Qualities.P1080.value, emitted.last().quality)
    }

    @Test
    fun testDeduplicatorConcurrentEmissionThreadSafety() {
        val emittedCount = AtomicInteger(0)
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedCount.incrementAndGet() }

        val numThreads = 50
        val executor = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(numThreads)

        for (i in 0 until numThreads) {
            val q = if (i == 25) Qualities.P2160.value else Qualities.P720.value
            executor.submit {
                try {
                    val link = createLink(
                        name = "Stream $q",
                        url = "https://cdn1.example.com/video.mp4?token=$i",
                        quality = q
                    )
                    deduplicator.emit(link)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(1, deduplicator.getEmittedCount())
        val finalLink = deduplicator.getEmittedLinks().first()
        assertEquals(Qualities.P2160.value, finalLink.quality)
    }
}
