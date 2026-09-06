package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Stress Challenge Test Suite for Milestone 2 Link Optimization Engine:
 * 1. Zero/Negative/Subnormal/NaN/Infinite durations in calculateBitrateKbps (no ArithmeticException).
 * 2. Unencoded spaces and special characters in canonicalStreamKey, resolveDirectStreamUrl, and optimize (no URISyntaxException).
 * 3. Direct video containers ending in .mp4 or .mkv with /hls/ or /m3u8/ in paths not misclassified as M3U8.
 * 4. Repeated formatLinkName and optimize calls are strictly idempotent (no badge duplication).
 * 5. High-concurrency emission (50-100 coroutines) into StreamDeduplicator under extreme contention.
 */
class StreamLinkOptimizerStressChallengerTest {

    private fun createLink(
        source: String = "StressSrc",
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

    // ==================== Challenge 1: calculateBitrateKbps Boundary Safety ====================

    @Test
    fun testCalculateBitrateKbpsZeroDurationSafe() {
        // Zero Double duration must not throw ArithmeticException (/ by zero) or produce Infinity
        val resultDouble = StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, 0.0)
        assertNull("Zero Double duration must return null", resultDouble)

        // Zero Long duration must not throw ArithmeticException
        val resultLong = StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, 0L)
        assertNull("Zero Long duration must return null", resultLong)
    }

    @Test
    fun testCalculateBitrateKbpsNegativeDurationsSafe() {
        assertNull("Negative Double duration must return null", StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, -1.0))
        assertNull("Negative large Double duration must return null", StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, -3600.5))
        assertNull("Negative Long duration must return null", StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, -100L))
    }

    @Test
    fun testCalculateBitrateKbpsZeroOrNegativeSizeBytesSafe() {
        assertNull("Zero sizeBytes must return null", StreamLinkOptimizer.calculateBitrateKbps(0L, 3600.0))
        assertNull("Negative sizeBytes must return null", StreamLinkOptimizer.calculateBitrateKbps(-500L, 3600.0))
        assertNull("Both zero must return null", StreamLinkOptimizer.calculateBitrateKbps(0L, 0.0))
    }

    @Test
    fun testCalculateBitrateKbpsNanAndInfiniteDurationsSafe() {
        assertNull("NaN duration must return null", StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, Double.NaN))
        assertNull("Positive Infinity duration must return null", StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, Double.POSITIVE_INFINITY))
        assertNull("Negative Infinity duration must return null", StreamLinkOptimizer.calculateBitrateKbps(1_000_000L, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun testCalculateBitrateKbpsExtremeValuesSafe() {
        // Very large size and 1 sec duration: totalBits exceeds normal int, must not throw or overflow
        val highBitrate = StreamLinkOptimizer.calculateBitrateKbps(10_000_000_000L, 1.0)
        assertNotNull("High bitrate must calculate finite Long", highBitrate)
        assertEquals(80_000_000L, highBitrate)

        // Normal 2 hour 1080p movie (4.5 GB in 7200s) -> ~5000 kbps
        val normalBitrate = StreamLinkOptimizer.calculateBitrateKbps(4_500_000_000L, 7200.0)
        assertNotNull(normalBitrate)
        assertEquals(5000L, normalBitrate)
    }

    // ==================== Challenge 2: Unencoded Spaces & Special Characters ====================

    @Test
    fun testCanonicalStreamKeyWithUnencodedSpacesDoesNotThrow() {
        val linkWithSpaces = createLink(url = "https://cdn.example.com/movies/My Video File With Spaces.mp4")
        val key = StreamLinkOptimizer.canonicalStreamKey(linkWithSpaces)
        assertTrue("Canonical key should handle unencoded spaces without throwing", key.isNotEmpty())
        assertTrue("Key should retain the file path", key.contains("My Video File With Spaces.mp4"))
    }

    @Test
    fun testCanonicalStreamKeyWithSpecialCharactersDoesNotThrow() {
        val linkSpecial = createLink(url = "https://cdn.example.com/stream/[1080p]/{season-1}/<preview>|file^name.mkv?token=abc&id=12345")
        val key = StreamLinkOptimizer.canonicalStreamKey(linkSpecial)
        assertTrue("Canonical key should handle brackets and special characters", key.isNotEmpty())
        assertFalse("Transient token must be stripped", key.contains("token=abc"))
        assertTrue("Content query id must be preserved", key.contains("id=12345"))
    }

    @Test
    fun testResolveDirectStreamUrlWithSpecialCharactersAndSpacesSafe() {
        // URLs with unencoded spaces or special characters must not throw URISyntaxException
        val rawUrlWithSpaces = "https://pixeldrain.com/u/abc12345?title=My Video File [1080p] With Spaces"
        val direct = StreamLinkOptimizer.resolveDirectStreamUrl(rawUrlWithSpaces)
        assertTrue("PixelDrain direct rewrite must succeed without throwing URISyntaxException", direct.contains("/api/file/abc12345?download"))

        val streamTapeUrl = "https://streamtape.com/get_video?id=tape123&title=Film With Spaces [Atmos]&auth=xyz"
        val streamTapeDirect = StreamLinkOptimizer.resolveDirectStreamUrl(streamTapeUrl)
        assertTrue("StreamTape stream=1 must be injected without throwing URISyntaxException", streamTapeDirect.contains("&stream=1") || streamTapeDirect.contains("?stream=1"))

        // Purely unescaped URLs must pass through safely without exception
        val arbitraryUrl = "https://cdn.example.com/watch?v=123&title=hello world&tag=<stream>"
        val safeResult = StreamLinkOptimizer.resolveDirectStreamUrl(arbitraryUrl)
        assertEquals(arbitraryUrl, safeResult)
    }

    @Test
    fun testOptimizeWithMalformedUrlSafe() {
        val malformedLink = createLink(url = "https://cdn.example.com/path with spaces/file [4K].mp4")
        val opt = StreamLinkOptimizer.optimize(malformedLink)
        assertNotNull(opt)
        assertEquals("https://cdn.example.com/path with spaces/file [4K].mp4", opt.url)
        assertEquals("https://cdn.example.com", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
        assertEquals("identity", opt.headers[StreamLinkOptimizer.HEADER_ACCEPT_ENCODING])
    }

    // ==================== Challenge 3: Container Resolution Edge Cases ====================

    @Test
    fun testDirectVideoContainerOverridesHlsPathTokens() {
        // URLs with /hls/ or /m3u8/ in directory path ending in .mp4 or .mkv must NOT be classified as M3U8
        val linkHlsMp4 = createLink(url = "https://cdn.com/hls/sample.mp4", type = ExtractorLinkType.M3U8)
        val optHlsMp4 = StreamLinkOptimizer.optimize(linkHlsMp4)
        assertEquals("Direct .mp4 inside /hls/ path must resolve to VIDEO", ExtractorLinkType.VIDEO, optHlsMp4.type)

        val linkM3u8Mkv = createLink(url = "https://cdn.com/m3u8/stream.mkv", type = ExtractorLinkType.M3U8)
        val optM3u8Mkv = StreamLinkOptimizer.optimize(linkM3u8Mkv)
        assertEquals("Direct .mkv inside /m3u8/ path must resolve to VIDEO", ExtractorLinkType.VIDEO, optM3u8Mkv.type)

        val linkHlsTranscode = createLink(url = "https://media.server.org/transcode/hls_source/output.webm")
        val optHlsTranscode = StreamLinkOptimizer.optimize(linkHlsTranscode)
        assertEquals("Direct .webm in transcode path must resolve to VIDEO", ExtractorLinkType.VIDEO, optHlsTranscode.type)
    }

    @Test
    fun testRealManifestsStillResolveCorrectly() {
        val linkHls = createLink(url = "https://cdn.com/stream/playlist.m3u8", type = ExtractorLinkType.VIDEO)
        assertEquals(ExtractorLinkType.M3U8, StreamLinkOptimizer.optimize(linkHls).type)

        val linkDash = createLink(url = "https://cdn.com/stream/manifest.mpd", type = ExtractorLinkType.VIDEO)
        assertEquals(ExtractorLinkType.DASH, StreamLinkOptimizer.optimize(linkDash).type)

        val linkAzure = createLink(url = "https://cdn.com/stream.ism/manifest(format=m3u8-aapl)")
        assertEquals(ExtractorLinkType.M3U8, StreamLinkOptimizer.optimize(linkAzure).type)
    }

    // ==================== Challenge 4: Audio Normalization & Badging Idempotency ====================

    @Test
    fun testFormatLinkNameStrictIdempotencyUnderRepeatedCalls() {
        val initialName = "Interstellar.2014.IMAX.Atmos.TrueHD.7.1.Dual-Audio.2160p"
        val badges = StreamLinkOptimizer.extractAudioBadges(initialName)

        var currentName = initialName
        repeat(10) {
            currentName = StreamLinkOptimizer.formatLinkName(
                currentName = currentName,
                quality = Qualities.P2160.value,
                url = "https://cdn.example.com/movie.mp4",
                bitrateKbps = 18_000L,
                audioBadges = badges
            )
        }

        fun countOccurrences(str: String, substr: String): Int =
            Regex(Regex.escape(substr), RegexOption.IGNORE_CASE).findAll(str).count()

        assertEquals("Quality [4K] must appear exactly once", 1, countOccurrences(currentName, "[4K]"))
        assertEquals("Bitrate [18 Mbps] must appear exactly once", 1, countOccurrences(currentName, "[18 Mbps]"))
        assertEquals("Audio badge [Atmos] must appear exactly once", 1, countOccurrences(currentName, "[Atmos]"))
        assertEquals("Audio badge [TrueHD] must appear exactly once", 1, countOccurrences(currentName, "[TrueHD]"))
        assertEquals("Audio badge [7.1] must appear exactly once", 1, countOccurrences(currentName, "[7.1]"))
        assertEquals("Audio badge [Dual Audio] must appear exactly once", 1, countOccurrences(currentName, "[Dual Audio]"))
    }

    @Test
    fun testOptimizeLinkRepeatedPassesIdempotency() {
        var link = createLink(
            name = "Inception 2010 1080p Atmos 5.1",
            url = "https://cdn.example.com/inception.mp4"
        )

        repeat(5) {
            link = StreamLinkOptimizer.optimize(link, durationSec = 7200.0, sizeBytes = 3_000_000_000L)
        }

        fun countOccurrences(str: String, substr: String): Int =
            Regex(Regex.escape(substr), RegexOption.IGNORE_CASE).findAll(str).count()

        assertEquals("[1080p] must not be duplicated", 1, countOccurrences(link.name, "[1080p]"))
        assertEquals("[Atmos] must not be duplicated", 1, countOccurrences(link.name, "[Atmos]"))
        assertEquals("[5.1] must not be duplicated", 1, countOccurrences(link.name, "[5.1]"))
    }

    // ==================== Challenge 5: High-Concurrency Thread Safety ====================

    @Test
    fun testHighConcurrency100CoroutinesSameKeyBitrateRace() = runBlocking {
        val emittedList = ConcurrentLinkedQueue<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        val totalCoroutines = 100
        val maxBitrateIndex = 77
        val maxBitrateKbps = 15000L

        // Launch 100 concurrent coroutines emitting to the same canonical stream
        val deferreds = (0 until totalCoroutines).map { i ->
            async(Dispatchers.Default) {
                val bitrate = if (i == maxBitrateIndex) maxBitrateKbps else (1000L + (i * 50L))
                val quality = if (i == maxBitrateIndex) Qualities.P2160.value else Qualities.P720.value
                val link = createLink(
                    name = "Stream [$bitrate kbps]",
                    url = "https://cdn1.example.com/video.mp4?token=token_$i&ts=${System.currentTimeMillis() + i}",
                    quality = quality
                )
                deduplicator.emit(link)
            }
        }

        deferreds.awaitAll()

        // Verify deduplication collapsed all 100 emissions to exactly 1 canonical stream
        assertEquals("Deduplicator must have exactly 1 unique stream", 1, deduplicator.getEmittedCount())
        val retainedLink = deduplicator.getEmittedLinks().first()
        assertEquals("Retained stream must be the maximum quality", Qualities.P2160.value, retainedLink.quality)
        assertTrue("Retained stream name must have highest bitrate", retainedLink.name.contains("15000 kbps"))
    }

    @Test
    fun testHighConcurrency100CoroutinesMultipleKeys() = runBlocking {
        val totalStreams = 10
        val duplicatesPerStream = 10
        val emittedList = ConcurrentLinkedQueue<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedList.add(it) }

        val deferreds = (0 until (totalStreams * duplicatesPerStream)).map { idx ->
            async(Dispatchers.Default) {
                val streamId = idx % totalStreams
                val variation = idx / totalStreams
                val quality = if (variation == 9) Qualities.P2160.value else Qualities.P480.value
                val link = createLink(
                    name = "Stream $streamId [Quality $quality]",
                    url = "https://node-$variation.streamhub.com/media/stream_$streamId.mp4?auth=nonce_$idx",
                    quality = quality
                )
                deduplicator.emit(link)
            }
        }

        deferreds.awaitAll()

        // 10 distinct streams across rotating nodes must collapse to exactly 10 deduplicated links
        assertEquals("Deduplicator must hold exactly $totalStreams unique streams", totalStreams, deduplicator.getEmittedCount())
        for (link in deduplicator.getEmittedLinks()) {
            assertEquals("Each retained stream must be upgraded to highest quality (2160p)", Qualities.P2160.value, link.quality)
        }
    }
}
