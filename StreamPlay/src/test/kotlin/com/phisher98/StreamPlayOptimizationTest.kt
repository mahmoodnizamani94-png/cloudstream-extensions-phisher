package com.phisher98

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamPlayOptimizationTest {

    @Before
    fun setUp() {
        StreamPlayCache.clearAllCachesForTesting()
    }

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

    // ==================== StreamPlayLinkOptimizer Tests ====================

    @Test
    fun testDownloadHeadersInjection() {
        val link = createLink(
            source = "TestCDN",
            name = "Test Stream",
            url = "https://cdn.example.com/video/1080p.mp4",
            type = ExtractorLinkType.VIDEO,
            referer = "https://cdn.example.com"
        )

        val optimized = StreamPlayLinkOptimizer.optimize(link)

        // Verify high-throughput download and anti-throttling headers
        assertEquals(USER_AGENT, optimized.headers[StreamPlayLinkOptimizer.HEADER_USER_AGENT])
        assertEquals("*/*", optimized.headers[StreamPlayLinkOptimizer.HEADER_ACCEPT])
        assertEquals("identity", optimized.headers[StreamPlayLinkOptimizer.HEADER_ACCEPT_ENCODING])
        assertEquals("keep-alive", optimized.headers[StreamPlayLinkOptimizer.HEADER_CONNECTION])
        assertEquals("https://cdn.example.com", optimized.headers[StreamPlayLinkOptimizer.HEADER_REFERER])
        assertEquals("https://cdn.example.com", optimized.headers[StreamPlayLinkOptimizer.HEADER_ORIGIN])
    }

    @Test
    fun testPixelDrainDirectDownloadUrlRewrite() {
        val viewLink = createLink(
            source = "PixelDrain",
            name = "File",
            url = "https://pixeldrain.com/u/abc123xy",
            type = ExtractorLinkType.VIDEO
        )
        val optimized = StreamPlayLinkOptimizer.optimize(viewLink)
        assertEquals("https://pixeldrain.com/api/file/abc123xy?download", optimized.url)
        assertFalse("PixelDrain headers should not have third-party referer", optimized.headers.containsKey("Referer"))
        assertEquals("PixelDrain effective referer must be empty to avoid anti-leech blocks", "", optimized.referer)

        // Test trailing slash and query param edge cases
        val viewLinkWithSlash = createLink(
            source = "PixelDrain",
            name = "FileSlash",
            url = "https://pixeldrain.com/u/abc123xy/?foo=bar",
            type = ExtractorLinkType.VIDEO
        )
        val optimizedSlash = StreamPlayLinkOptimizer.optimize(viewLinkWithSlash)
        assertEquals("https://pixeldrain.com/api/file/abc123xy?download", optimizedSlash.url)
        assertEquals("", optimizedSlash.referer)

        val fileLinkWithoutDownload = createLink(
            source = "PixelDrain",
            name = "File2",
            url = "https://pixeldrain.com/api/file/abc123xy",
            type = ExtractorLinkType.VIDEO
        )
        val optimizedFile = StreamPlayLinkOptimizer.optimize(fileLinkWithoutDownload)
        assertEquals("https://pixeldrain.com/api/file/abc123xy?download", optimizedFile.url)
        assertEquals("", optimizedFile.referer)
    }

    @Test
    fun testGofileHeaderTuning() {
        val link = createLink(
            source = "Gofile",
            name = "Video",
            url = "https://srv-store1.gofile.io/download/direct/abc/movie.mp4",
            type = ExtractorLinkType.VIDEO
        )
        val optimized = StreamPlayLinkOptimizer.optimize(link)
        assertEquals("https://gofile.io/", optimized.headers["Referer"])
        assertEquals("https://gofile.io", optimized.headers["Origin"])
        assertEquals("https://gofile.io/", optimized.referer)
        assertEquals("identity", optimized.headers["Accept-Encoding"])
        assertEquals("keep-alive", optimized.headers["Connection"])
    }

    @Test
    fun testStreamTypeResolution() {
        val m3u8Link = createLink(
            source = "Test",
            name = "Test",
            url = "https://stream.host.com/playlist.m3u8?token=xyz",
            type = ExtractorLinkType.VIDEO
        )
        val optM3u8 = StreamPlayLinkOptimizer.optimize(m3u8Link)
        assertEquals(ExtractorLinkType.M3U8, optM3u8.type)

        val mp4Link = createLink(
            source = "Test",
            name = "Test",
            url = "https://direct.host.com/video.mp4",
            type = ExtractorLinkType.M3U8
        )
        val optMp4 = StreamPlayLinkOptimizer.optimize(mp4Link)
        assertEquals(ExtractorLinkType.VIDEO, optMp4.type)

        // Critical edge case: direct MP4 containing 'hls' substring in domain or path must NOT be misclassified as M3U8
        val hlsPathMp4 = createLink(
            source = "Test",
            name = "Test",
            url = "https://example.com/hls-download/video.mp4",
            type = ExtractorLinkType.VIDEO
        )
        assertEquals(ExtractorLinkType.VIDEO, StreamPlayLinkOptimizer.resolveCorrectLinkType(hlsPathMp4.url, hlsPathMp4.type))

        val hlsDomainMkv = createLink(
            source = "Test",
            name = "Test",
            url = "https://hls.server.com/film.mkv",
            type = ExtractorLinkType.VIDEO
        )
        assertEquals(ExtractorLinkType.VIDEO, StreamPlayLinkOptimizer.resolveCorrectLinkType(hlsDomainMkv.url, hlsDomainMkv.type))
    }

    @Test
    fun testDangerousRangeHeaderStripped() {
        val linkWithRange = createLink(
            source = "UpstreamExtractor",
            name = "RangeTest",
            url = "https://cdn.example.com/video.mp4",
            headers = mapOf("Range" to "bytes=0-", "Accept" to "*/*")
        )
        val optimized = StreamPlayLinkOptimizer.optimize(linkWithRange)
        assertFalse("Static Range: bytes=0- must be stripped to prevent corrupt chunk downloads", optimized.headers.containsKey("Range"))
        assertFalse("Static Range case-insensitive check", optimized.headers.keys.any { it.equals("range", ignoreCase = true) })
    }

    @Test
    fun testQualityExtractionAndFormatting() {
        assertEquals(Qualities.P2160.value, StreamPlayLinkOptimizer.extractQualityFromText("Movie 2024 2160p HDR", null))
        assertEquals(Qualities.P2160.value, StreamPlayLinkOptimizer.extractQualityFromText("Movie 4K UHD", null))
        assertEquals(Qualities.P1080.value, StreamPlayLinkOptimizer.extractQualityFromText("Movie 1080p FHD", null))
        assertEquals(Qualities.P720.value, StreamPlayLinkOptimizer.extractQualityFromText("Movie 720p HD", null))

        val name = StreamPlayLinkOptimizer.formatLinkName("Direct Stream", Qualities.P1080.value, "https://example.com/v.mp4")
        assertTrue("Formatted name contains quality tag", name.contains("[1080p]"))
    }

    @Test
    fun testCanonicalDeduplicationKey() {
        val link1 = createLink(
            source = "Src",
            name = "Name",
            url = "https://cdn.example.com/stream/v1?token=123&t=99999&session=abc",
            type = ExtractorLinkType.VIDEO,
            quality = Qualities.P1080.value
        )
        val link2 = createLink(
            source = "Src",
            name = "Name",
            url = "https://cdn.example.com/stream/v1?session=xyz&token=123&t=11111",
            type = ExtractorLinkType.VIDEO,
            quality = Qualities.P1080.value
        )

        val key1 = StreamPlayLinkOptimizer.canonicalStreamKey(link1)
        val key2 = StreamPlayLinkOptimizer.canonicalStreamKey(link2)

        assertEquals("Canonical keys match after volatile param stripping", key1, key2)
    }

    // ==================== StreamPlayCache LRU & Circuit Breaker Tests ====================

    @Test
    fun testLruCacheBasicPutGetAndEviction() {
        val cache = StreamPlayCache.LruCacheWithTtl<String, String>(maxSize = 3, defaultTtlMillis = 60000L)
        cache.put("k1", "v1")
        cache.put("k2", "v2")
        cache.put("k3", "v3")

        assertEquals("v1", cache.get("k1"))
        assertEquals("v2", cache.get("k2"))
        assertEquals("v3", cache.get("k3"))

        // Access k1 and k2 so k3 is least recently used
        cache.get("k1")
        cache.get("k2")

        // Put k4 -> k3 should be evicted
        cache.put("k4", "v4")

        assertEquals("v1", cache.get("k1"))
        assertEquals("v2", cache.get("k2"))
        assertNull("k3 should be evicted", cache.get("k3"))
        assertEquals("v4", cache.get("k4"))
        assertTrue(cache.size <= 3)
    }

    @Test
    fun testLruCacheTtlExpiration() {
        val cache = StreamPlayCache.LruCacheWithTtl<String, String>(maxSize = 10, defaultTtlMillis = 50L)
        cache.put("expiring", "value", ttlMillis = 40L)
        assertEquals("value", cache.get("expiring"))

        Thread.sleep(60L)
        assertNull("Value should expire after TTL", cache.get("expiring"))
    }

    @Test
    fun testLruCacheThreadSafety() {
        val cache = StreamPlayCache.LruCacheWithTtl<Int, String>(maxSize = 64, defaultTtlMillis = 60000L)
        val threadCount = 8
        val opsPerThread = 300
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        val key = (t * 50 + i) % 100
                        cache.put(key, "data-$key")
                        val retrieved = cache.get(key)
                        if (retrieved != null) {
                            assertEquals("data-$key", retrieved)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val success = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Concurrent access completed without deadlock", success)
        assertTrue("Cache size bounded by maxSize", cache.size <= 64)
    }

    @Test
    fun testProviderCircuitBreakerScoring() {
        val provider = "SlowOrBrokenProvider"

        // 0 executions -> default score 0
        assertEquals(0f, StreamPlayCache.getProviderPriorityScore(provider), 0.01f)

        // Record 1 success of 1000ms -> score = 100 - (1000/1000) = 99
        StreamPlayCache.recordProviderExecution(provider, success = true, durationMs = 1000L)
        val initialScore = StreamPlayCache.getProviderPriorityScore(provider)
        assertEquals(99f, initialScore, 0.01f)

        // Record 5 consecutive failures to trigger circuit breaker
        for (i in 1..5) {
            StreamPlayCache.recordProviderExecution(provider, success = false, durationMs = 5000L)
        }

        val stats = StreamPlayCache.getProviderStats(provider)
        assertTrue("Circuit breaker must be tripped", stats.isCircuitBroken)
        assertEquals(-1000f, StreamPlayCache.getProviderPriorityScore(provider), 0.01f)

        // A single successful execution must recover the provider
        StreamPlayCache.recordProviderExecution(provider, success = true, durationMs = 800L)
        val recoveredStats = StreamPlayCache.getProviderStats(provider)
        assertFalse("Provider must be recovered after success", recoveredStats.isCircuitBroken)
        assertTrue("Score restored after recovery", StreamPlayCache.getProviderPriorityScore(provider) > 0f)
    }

    // ==================== StreamPlayConcurrency Tests ====================

    @Test
    fun testConcurrencyNormalizationAndLabels() {
        assertEquals(StreamPlayConcurrency.MIN_PROVIDER_CONCURRENCY, StreamPlayConcurrency.normalizeConcurrency(1))
        assertEquals(StreamPlayConcurrency.MAX_PROVIDER_CONCURRENCY, StreamPlayConcurrency.normalizeConcurrency(200))
        assertEquals(32, StreamPlayConcurrency.normalizeConcurrency(32))

        assertEquals("Slow internet saver", StreamPlayConcurrency.concurrencyLabel(10))
        assertEquals("Balanced", StreamPlayConcurrency.concurrencyLabel(24))
        assertEquals("Fast", StreamPlayConcurrency.concurrencyLabel(48))
        assertEquals("Max speed", StreamPlayConcurrency.concurrencyLabel(80))
    }

    @Test
    fun testSlowInternetSearchCondition() {
        assertFalse("Should not stop with few links", StreamPlayConcurrency.shouldStopSlowInternetSearch(3, 1, 10, 40))
        assertFalse("Should not stop with few providers completed", StreamPlayConcurrency.shouldStopSlowInternetSearch(10, 1, 4, 40))
        assertTrue("Should stop when threshold met with subtitles", StreamPlayConcurrency.shouldStopSlowInternetSearch(8, 1, 10, 40))
        assertTrue("Should stop when threshold met with sufficient providers completed", StreamPlayConcurrency.shouldStopSlowInternetSearch(8, 0, 18, 40))
    }

    @Test
    fun testSupervisedConcurrencyShortCircuit() = runBlocking {
        val completedCount = AtomicInteger(0)
        val linksCounter = AtomicInteger(0)

        // Create 20 simulated scraper tasks: each takes 500ms
        val tasks = (1..20).map { i ->
            suspend {
                delay(100)
                linksCounter.incrementAndGet()
                completedCount.incrementAndGet()
                delay(400) // simulates slow straggler
            }
        }

        val isSatisfied = {
            // Early satisfaction condition: stop once 5 links are found
            linksCounter.get() >= 5
        }

        val startTime = System.currentTimeMillis()

        StreamPlayConcurrency.runSupervisedLimitedAsync(
            concurrency = 8,
            taskTimeoutMs = 10000L,
            isSatisfied = isSatisfied,
            tasks = tasks
        )

        val duration = System.currentTimeMillis() - startTime

        // Should complete in well under 1.5 seconds instead of waiting for all 20 * 500ms
        assertTrue("Short circuit completed rapidly (took ${duration}ms)", duration < 2500L)
        assertTrue("Found at least 5 links before short circuit", linksCounter.get() >= 5)
    }

    @Test
    fun testEmptyAndMalformedUrlsHandling() {
        val emptyLink = createLink(url = "")
        val optEmpty = StreamPlayLinkOptimizer.optimize(emptyLink)
        assertEquals("", optEmpty.url)

        val whitespaceLink = createLink(url = "   ")
        val optWhitespace = StreamPlayLinkOptimizer.optimize(whitespaceLink)
        assertEquals("   ", optWhitespace.url)

        val malformedLink = createLink(url = "https://cdn.example.com/video path with spaces/file.mp4?arg=val 1")
        val optMalformed = StreamPlayLinkOptimizer.optimize(malformedLink)
        assertNotNull(optMalformed)
        assertEquals(ExtractorLinkType.VIDEO, optMalformed.type)
        assertEquals("https://cdn.example.com", optMalformed.headers[StreamPlayLinkOptimizer.HEADER_REFERER])

        val canonicalMalformed = StreamPlayLinkOptimizer.canonicalStreamKey(malformedLink)
        assertNotNull(canonicalMalformed)
    }

    @Test
    fun testDashAndMiscellaneousFormats() {
        val dashLink = createLink(url = "https://cdn.example.com/manifest.mpd", type = ExtractorLinkType.VIDEO)
        assertEquals(ExtractorLinkType.DASH, StreamPlayLinkOptimizer.resolveCorrectLinkType(dashLink.url, dashLink.type))

        val webmLink = createLink(url = "https://cdn.example.com/movie.webm", type = ExtractorLinkType.M3U8)
        assertEquals(ExtractorLinkType.VIDEO, StreamPlayLinkOptimizer.resolveCorrectLinkType(webmLink.url, webmLink.type))

        val mkvLink = createLink(url = "https://cdn.example.com/movie.mkv", type = ExtractorLinkType.M3U8)
        assertEquals(ExtractorLinkType.VIDEO, StreamPlayLinkOptimizer.resolveCorrectLinkType(mkvLink.url, mkvLink.type))
    }

    @Test
    fun testConcurrencyExtremeInputs() {
        assertEquals(StreamPlayConcurrency.MIN_PROVIDER_CONCURRENCY, StreamPlayConcurrency.normalizeConcurrency(-10))
        assertEquals(StreamPlayConcurrency.MIN_PROVIDER_CONCURRENCY, StreamPlayConcurrency.normalizeConcurrency(0))
        assertEquals(StreamPlayConcurrency.MAX_PROVIDER_CONCURRENCY, StreamPlayConcurrency.normalizeConcurrency(99999))
    }
}
