package com.phisher98

import android.content.Context
import org.jsoup.Jsoup
import org.junit.After
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Milestone 4 Empirical Adversarial Challenger Test Suite
 *
 * Exhaustively stress-tests:
 * 1. DeviceProfiler:
 *    - Physical RAM boundary values (1.5GB, 2.0GB, 3.0GB)
 *    - memoryClass boundary values (192MB, 384MB)
 *    - Processor core boundary values (4 cores, 6 cores, 8 cores)
 *    - Exact Dalvik/JVM heap headroom boundary (< 32MB threshold vs exactly 32MB)
 *    - 50% concurrency clamping and floor coercion (MIN_CONCURRENCY = 2)
 *    - 500ms volatile cache TTL validation, cache hits, eviction, and force refresh
 *    - High-concurrency query race safety under concurrent cache invalidation
 *
 * 2. ZeroAllocParser:
 *    - Parity vs Jsoup on SuperStream file_quality blocks with varied attributes, quote styles, nested tags, and URL entity decoding
 *    - Parity vs Jsoup on standard, uppercase, lazy (data-src), and malformed iframe tags
 *    - Parity vs Jsoup on <script> blocks containing markers
 *    - Balanced JSON and JS array extractions with nested braces, brackets, and escaped quotes
 *    - JavaScript variable assignment extractions (var, let, const, single, double, unquoted)
 *    - Fuzzing with malformed HTML, partial tags, unclosed quotes, and 500-tag payload benchmarks
 */
class Milestone4ChallengerAdversarialTest {

    // Test Doubles for DeviceProfiler
    class AdversarialRuntimeMetrics(
        var processors: Int = 8,
        var maxHeap: Long = 512L * 1024L * 1024L,
        var totalHeap: Long = 128L * 1024L * 1024L,
        var freeHeap: Long = 64L * 1024L * 1024L
    ) : RuntimeMetricsProvider {
        override fun availableProcessors(): Int = processors
        override fun maxMemory(): Long = maxHeap
        override fun totalMemory(): Long = totalHeap
        override fun freeMemory(): Long = freeHeap
    }

    class AdversarialMemoryInfo(
        var totalRam: Long = 6L * 1024L * 1024L * 1024L,
        var availRam: Long = 3L * 1024L * 1024L * 1024L,
        var memClass: Int = 512,
        var largeMemClass: Int = 512,
        var isLowRam: Boolean = false,
        var isLowMem: Boolean = false,
        var overrideHeadroom: Long? = null,
        val snapshotCallCount: AtomicInteger = AtomicInteger(0)
    ) : MemoryInfoProvider {
        override fun getMemorySnapshot(context: Context?, runtimeMetrics: RuntimeMetricsProvider): MemorySnapshot {
            snapshotCallCount.incrementAndGet()
            val maxHeap = runtimeMetrics.maxMemory()
            val totalHeap = runtimeMetrics.totalMemory()
            val freeHeap = runtimeMetrics.freeMemory()
            val usedHeap = totalHeap - freeHeap
            val headroom = overrideHeadroom ?: if (maxHeap == Long.MAX_VALUE) freeHeap else (maxHeap - usedHeap).coerceAtLeast(0L)
            return MemorySnapshot(
                totalRamBytes = totalRam,
                availableRamBytes = availRam,
                freeHeapBytes = headroom,
                maxHeapBytes = maxHeap,
                isLowMemory = isLowMem
            )
        }

        override fun getMemoryClass(context: Context?): Int = memClass
        override fun getLargeMemoryClass(context: Context?): Int = largeMemClass
        override fun isLowRamDevice(context: Context?): Boolean = isLowRam
    }

    private lateinit var mockRuntime: AdversarialRuntimeMetrics
    private lateinit var mockMemory: AdversarialMemoryInfo

    @Before
    fun setUp() {
        DeviceProfiler.resetForTesting()
        mockRuntime = AdversarialRuntimeMetrics()
        mockMemory = AdversarialMemoryInfo()
        DeviceProfiler.setRuntimeMetricsProviderForTesting(mockRuntime)
        DeviceProfiler.setMemoryInfoProviderForTesting(mockMemory)
    }

    @After
    fun tearDown() {
        DeviceProfiler.resetForTesting()
    }

    // =========================================================================
    // PART 1: DeviceProfiler Hardware Tier Boundary Stress Tests
    // =========================================================================

    @Test
    fun testDeviceTierRamBoundary1536Mb() {
        val ram1536Mb = 1536L * 1024L * 1024L
        mockMemory.memClass = 256
        mockRuntime.processors = 6

        // Case 1: Exactly 1.5GB RAM (1536MB) -> LOW_END
        mockMemory.totalRam = ram1536Mb
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())
        assertEquals(12, DeviceProfiler.getActiveConcurrency())

        // Case 2: 1.5GB - 1 byte -> LOW_END
        mockMemory.totalRam = ram1536Mb - 1L
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())
        assertEquals(12, DeviceProfiler.getActiveConcurrency())

        // Case 3: 1.5GB + 1 byte with 6 cores, 256MB memClass -> MID_RANGE
        mockMemory.totalRam = ram1536Mb + 1L
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())
        assertEquals(36, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testDeviceTierCoresAndRamBoundary2048Mb() {
        mockMemory.memClass = 256

        // Case 1: 2048MB RAM with 4 cores -> LOW_END (processors <= 4 && totalRamMb <= 2048)
        mockMemory.totalRam = 2048L * 1024L * 1024L
        mockRuntime.processors = 4
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())

        // Case 2: 2048MB RAM with 5 cores -> MID_RANGE
        mockRuntime.processors = 5
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())

        // Case 3: 2049MB RAM with 4 cores -> MID_RANGE (totalRamMb = 2049 > 2048)
        mockMemory.totalRam = 2049L * 1024L * 1024L
        mockRuntime.processors = 4
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())
    }

    @Test
    fun testDeviceTierRamBoundary3072Mb() {
        val ram3072Mb = 3072L * 1024L * 1024L
        mockMemory.memClass = 512
        mockRuntime.processors = 8

        // Case 1: Exactly 3.0GB (3072MB) -> MID_RANGE (totalRamBytes <= 3072MB)
        mockMemory.totalRam = ram3072Mb
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())
        assertEquals(36, DeviceProfiler.getActiveConcurrency())

        // Case 2: 3.0GB + 1 byte with flagship specs (memClass > 384, cores > 6) -> HIGH_END
        mockMemory.totalRam = ram3072Mb + 1L
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.HIGH_END, DeviceProfiler.getDeviceTier())
        assertEquals(64, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testDeviceTierMemoryClassBoundaryValues() {
        mockMemory.totalRam = 4L * 1024L * 1024L * 1024L
        mockRuntime.processors = 8

        // Exactly 192MB memClass -> LOW_END
        mockMemory.memClass = 192
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())

        // 193MB memClass (with 4GB RAM and 8 cores) -> HIGH_END
        mockMemory.memClass = 193
        DeviceProfiler.resetCacheForTesting()
        // Wait: memClass 193 <= 384, so it is MID_RANGE!
        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())

        // Exactly 384MB memClass -> MID_RANGE
        mockMemory.memClass = 384
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())

        // 385MB memClass -> HIGH_END (RAM > 3GB, cores > 6, memClass > 384)
        mockMemory.memClass = 385
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.HIGH_END, DeviceProfiler.getDeviceTier())
    }

    @Test
    fun testDeviceTierProcessorCountBoundaryValues() {
        mockMemory.totalRam = 4L * 1024L * 1024L * 1024L
        mockMemory.memClass = 512

        // Exactly 6 cores -> MID_RANGE (processors <= 6)
        mockRuntime.processors = 6
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())

        // 7 cores -> HIGH_END (cores > 6)
        mockRuntime.processors = 7
        DeviceProfiler.resetCacheForTesting()
        assertEquals(DeviceTier.HIGH_END, DeviceProfiler.getDeviceTier())
    }

    // =========================================================================
    // PART 2: Real-Time Heap Governor & 50% Clamping Boundary Tests
    // =========================================================================

    @Test
    fun testExactHeadroomThresholdBoundary() {
        val threshold = DeviceProfiler.LOW_HEAP_HEADROOM_THRESHOLD_BYTES // 33,554,432L (32MB)

        // Snapshot with freeHeapBytes == 32MB exactly: NOT constrained (< 32MB threshold)
        val snapshotAtThreshold = MemorySnapshot(
            totalRamBytes = 4L * 1024L * 1024L * 1024L,
            availableRamBytes = 2L * 1024L * 1024L * 1024L,
            freeHeapBytes = threshold,
            maxHeapBytes = 256L * 1024L * 1024L,
            isLowMemory = false
        )
        assertFalse("At exactly 32MB, heap should not be constrained", snapshotAtThreshold.isHeapConstrained)
        assertEquals(64, DeviceProfiler.clampConcurrencyForHeap(64, snapshotAtThreshold))

        // Snapshot with freeHeapBytes == 32MB - 1 byte: CONSTRAINED
        val snapshotBelowThreshold = snapshotAtThreshold.copy(freeHeapBytes = threshold - 1L)
        assertTrue("At 32MB - 1 byte, heap must be constrained", snapshotBelowThreshold.isHeapConstrained)
        assertEquals(32, DeviceProfiler.clampConcurrencyForHeap(64, snapshotBelowThreshold))

        // Zero and negative headroom handling
        val snapshotZero = snapshotAtThreshold.copy(freeHeapBytes = 0L)
        assertTrue(snapshotZero.isHeapConstrained)
        assertEquals(32, DeviceProfiler.clampConcurrencyForHeap(64, snapshotZero))
    }

    @Test
    fun testClampingCalculationsAcrossTiersAndCustomBases() {
        val constrainedSnapshot = MemorySnapshot(
            totalRamBytes = 2L * 1024L * 1024L * 1024L,
            availableRamBytes = 500L * 1024L * 1024L,
            freeHeapBytes = 16L * 1024L * 1024L, // 16MB < 32MB
            maxHeapBytes = 128L * 1024L * 1024L,
            isLowMemory = false
        )

        // High-end (64) -> 32
        assertEquals(32, DeviceProfiler.clampConcurrencyForHeap(64, constrainedSnapshot))

        // Mid-range (36) -> 18
        assertEquals(18, DeviceProfiler.clampConcurrencyForHeap(36, constrainedSnapshot))

        // Low-end (12) -> 6
        assertEquals(6, DeviceProfiler.clampConcurrencyForHeap(12, constrainedSnapshot))

        // Custom odd bases: 15 -> 7, 5 -> 2
        assertEquals(7, DeviceProfiler.clampConcurrencyForHeap(15, constrainedSnapshot))
        assertEquals(2, DeviceProfiler.clampConcurrencyForHeap(5, constrainedSnapshot))

        // Floor coercion: base = 3 -> 3 / 2 = 1, coerced to MIN_CONCURRENCY (2)
        assertEquals(2, DeviceProfiler.clampConcurrencyForHeap(3, constrainedSnapshot))
        assertEquals(2, DeviceProfiler.clampConcurrencyForHeap(2, constrainedSnapshot))
    }

    // =========================================================================
    // PART 3: 500ms Cache TTL Behavior & Invalidation
    // =========================================================================

    @Test
    fun testSnapshotCacheTtlAndForceRefresh() {
        mockMemory.totalRam = 4L * 1024L * 1024L * 1024L
        mockMemory.overrideHeadroom = 100L * 1024L * 1024L
        mockMemory.snapshotCallCount.set(0)

        // First call populates cache
        val snap1 = DeviceProfiler.getMemorySnapshot(null)
        assertEquals(1, mockMemory.snapshotCallCount.get())

        // Repeated immediate calls within 500ms return cached snapshot without calling provider
        for (i in 1..20) {
            val snapCached = DeviceProfiler.getMemorySnapshot(null)
            assertTrue("Snapshot should be cached reference", snapCached === snap1)
        }
        assertEquals("Provider should not be called again within TTL window", 1, mockMemory.snapshotCallCount.get())

        // Force refresh immediately queries provider
        val snapForced = DeviceProfiler.getMemorySnapshot(null, forceRefresh = true)
        assertEquals(2, mockMemory.snapshotCallCount.get())
        assertNotNull(snapForced)

        // After cache reset, queries provider
        DeviceProfiler.resetCacheForTesting()
        val snapAfterReset = DeviceProfiler.getMemorySnapshot(null)
        assertEquals(3, mockMemory.snapshotCallCount.get())
        assertNotNull(snapAfterReset)

        // Verify sleep > 500ms causes TTL expiry
        Thread.sleep(550L)
        val snapExpired = DeviceProfiler.getMemorySnapshot(null)
        assertEquals(4, mockMemory.snapshotCallCount.get())
        assertNotNull(snapExpired)
    }

    // =========================================================================
    // PART 4: Multi-Threaded Query & Cache Concurrency Stress
    // =========================================================================

    @Test
    fun testDeviceProfilerConcurrentStressWithCacheInvalidation() {
        val numThreads = 32
        val iterationsPerThread = 1000
        val latch = CountDownLatch(numThreads)
        val executor = Executors.newFixedThreadPool(numThreads)
        val errorCount = AtomicInteger(0)

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        if (i % 100 == 0) {
                            DeviceProfiler.resetCacheForTesting()
                        }
                        val concurrency = DeviceProfiler.getActiveConcurrency(null)
                        if (concurrency < DeviceProfiler.MIN_CONCURRENCY || concurrency > DeviceTier.HIGH_END.maxConcurrency) {
                            errorCount.incrementAndGet()
                        }
                        val isConstrained = DeviceProfiler.isHeapConstrained(null)
                        // Concurrency must never throw or produce invalid states
                        assertNotNull(isConstrained)
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Multi-threaded stress harness must complete within 10 seconds", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Zero errors under multi-threaded concurrency and cache invalidation", 0, errorCount.get())
    }

    // =========================================================================
    // PART 5: ZeroAllocParser Parity vs Jsoup on SuperStream
    // =========================================================================

    private fun jsoupSuperStreamExtractor(html: String): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<Triple<String, String, String>>()
        doc.select("div.file_quality").forEach { el ->
            val url = el.attr("data-url").takeIf { it.isNotEmpty() } ?: return@forEach
            val quality = el.attr("data-quality").takeIf { it.isNotEmpty() } ?: return@forEach
            val size = el.selectFirst(".size")?.text()?.takeIf { it.isNotEmpty() } ?: return@forEach
            results.add(Triple(ZeroAllocParser.fastUnescapeSlash(url), quality, size))
        }
        return results
    }

    @Test
    fun testSuperStreamJsoupParityComplexAttributesAndEscaping() {
        val complexHtml = """
            <div class="header"><h1>Streams</h1></div>
            <div class="main-content">
                <!-- Server 1: standard attributes -->
                <div class="file_quality" data-url="https:\/\/febbox.com\/videos\/stream_1080p.mp4?sig=abc&amp;expire=123" data-quality="1080p">
                    <span class="icon">HD</span>
                    <span class="size"> 2.85 GB </span>
                </div>
                <!-- Server 2: reversed attribute order (data-quality before data-url) -->
                <div class="file_quality" data-quality="720p" data-url="https:\/\/febbox.com\/videos\/stream_720p.mp4" id="quality_item_2">
                    <span class="badge badge-warning">Fast</span>
                    <span class="size">1.20 GB</span>
                </div>
                <!-- Server 3: single-quoted attributes and extra classes -->
                <div class='other-class file_quality secondary' data-url='https://febbox.com/videos/stream_480p.mp4' data-quality='480p'>
                    <div class="meta">
                        <span class="size">650 MB</span>
                    </div>
                </div>
                <!-- Server 4: ORG quality token -->
                <div class="file_quality" data-url="https:\/\/febbox.com\/videos\/movie_2160p_remux.mkv" data-quality="ORG">
                    <span class="size">14.20 GB</span>
                </div>
            </div>
        """.trimIndent()

        val jsoupResults = jsoupSuperStreamExtractor(complexHtml)
        val zeroAllocResults = ZeroAllocParser.extractFileQualities(complexHtml)

        assertEquals("ZeroAllocParser and Jsoup must find the exact same number of items", jsoupResults.size, zeroAllocResults.size)
        assertEquals(4, zeroAllocResults.size)

        for (i in jsoupResults.indices) {
            val j = jsoupResults[i]
            val z = zeroAllocResults[i]
            assertEquals("URL parity mismatch at index $i", j.first, z.first)
            assertEquals("Quality parity mismatch at index $i", j.second, z.second)
            assertEquals("Size parity mismatch at index $i", j.third, z.third)
        }

        // Test extractSuperStreamQualities ORG resolution parity
        val resolvedQualities = ZeroAllocParser.extractSuperStreamQualities(complexHtml)
        assertEquals(4, resolvedQualities.size)
        assertEquals("1080p", resolvedQualities[0].quality)
        assertEquals("720p", resolvedQualities[1].quality)
        assertEquals("480p", resolvedQualities[2].quality)
        assertEquals("2160p", resolvedQualities[3].quality) // extracted from URL "2160p"
    }

    // =========================================================================
    // PART 6: ZeroAllocParser Iframe Parity vs Jsoup
    // =========================================================================

    @Test
    fun testIframeParityJsoup() {
        val testCases = listOf(
            // 1. Standard iframe
            """<iframe src="https://embed.streamtape.com/e/12345" width="100%" height="100%"></iframe>""",
            // 2. Uppercase IFRAME and SRC
            """<IFRAME class="embed-player" SRC="https://vidplay.online/e/xyz890" allowfullscreen></IFRAME>""",
            // 3. Lazy loading data-src
            """<iframe data-src="https://lazy.com/player/999" width="500"></iframe>""",
            // 4. Blank data-src with fallback to src
            """<iframe data-src="" src="https://cdn.com/fallback_player"></iframe>""",
            // 5. HTML entities in src
            """<iframe src="https://example.com/play?id=123&amp;key=abc&quot;def&quot;&apos;ghi&apos;&lt;xyz&gt;"></iframe>""",
            // 6. Multi-line iframe with newlines and spaces
            """<iframe
                   id="main_frame"
                   src="https://multiline.com/video/embed"
                   width="100%"
               ></iframe>"""
        )

        for (html in testCases) {
            val zeroAllocSrc = ZeroAllocParser.extractIframeSrc(html)
            assertNotNull("Extracted iframe src should not be null for: $html", zeroAllocSrc)

            // Validate against Jsoup
            val doc = Jsoup.parse(html)
            val iframeEl = doc.selectFirst("iframe")
            assertNotNull(iframeEl)
            val expectedSrc = if (!iframeEl!!.attr("data-src").isNullOrBlank()) {
                iframeEl.attr("data-src")
            } else {
                iframeEl.attr("src")
            }
            assertEquals("Iframe src mismatch for: $html", expectedSrc, zeroAllocSrc)
        }
    }

    // =========================================================================
    // PART 7: ZeroAllocParser Script Block Parity vs Jsoup
    // =========================================================================

    @Test
    fun testScriptExtractionParityWithJsoup() {
        val html = """
            <html>
                <head>
                    <script type="text/javascript">var mode = 'night';</script>
                    <SCRIPT type="application/javascript">
                        var playerOptions = {
                            file: "https://stream.server.org/master.m3u8",
                            targetId: "video-container"
                        };
                    </SCRIPT>
                </head>
                <body>
                    <script>
                        console.log("analytics");
                    </script>
                </body>
            </html>
        """.trimIndent()

        // Jsoup baseline
        val jsoupData = Jsoup.parse(html).selectFirst("script:containsData(targetId:)")?.data()?.trim()
        val zeroAllocData = ZeroAllocParser.extractScriptContaining(html, "targetId:")?.trim()

        assertNotNull(jsoupData)
        assertNotNull(zeroAllocData)
        assertEquals(jsoupData, zeroAllocData)
        assertTrue(zeroAllocData!!.contains("https://stream.server.org/master.m3u8"))
    }

    // =========================================================================
    // PART 8: Balanced JSON & Array Block Token Scanner Stress Tests
    // =========================================================================

    @Test
    fun testBalancedJsonBlockExtractionWithNestedStructuresAndEscapes() {
        val script = """
            const appConfig = {
                "server": {
                    "host": "api.cloudstream.tv",
                    "routes": { "v1": "/v1/api", "v2": "/v2/api" }
                },
                "nestedBracesInString": "This string has { brackets } and \"escaped quotes\" inside!",
                "active": true
            };
            var otherVar = 42;
        """.trimIndent()

        val jsonBlock = ZeroAllocParser.extractJsonBlock(script, "appConfig")
        assertNotNull("JSON block should be extracted", jsonBlock)
        assertTrue(jsonBlock!!.startsWith("{"))
        assertTrue(jsonBlock.endsWith("}"))
        assertTrue(jsonBlock.contains("nestedBracesInString"))
        assertTrue(jsonBlock.contains("escaped quotes"))

        // Unbalanced / truncated JSON must safely return null without infinite loops
        val truncatedScript = """const broken = { "name": "incomplete", "sub": { "a": 1 };"""
        val brokenBlock = ZeroAllocParser.extractJsonBlock(truncatedScript, "broken")
        assertNull("Truncated JSON must return null", brokenBlock)
    }

    @Test
    fun testBalancedArrayBlockExtraction() {
        val js = """
            var streamUrls = [
                "https://cdn1.tv/track1.mp4",
                [ "nested", "array", [ 1, 2, 3 ] ],
                "string with [brackets] inside"
            ];
        """.trimIndent()

        val arrayBlock = ZeroAllocParser.extractArrayBlock(js, "streamUrls")
        assertNotNull("Array block should be extracted", arrayBlock)
        assertTrue(arrayBlock!!.startsWith("["))
        assertTrue(arrayBlock.endsWith("]"))
        assertTrue(arrayBlock.contains("string with [brackets] inside"))

        // Truncated array must safely return null
        val truncatedJs = """var badArray = [ 1, 2, 3;"""
        assertNull("Truncated array must return null", ZeroAllocParser.extractArrayBlock(truncatedJs, "badArray"))
    }

    @Test
    fun testJsVariableAssignmentExtraction() {
        val js = """
            var standardUrl = "https://cdn.tv/stream.m3u8";
            let singleQuote = 'my_secret_token_value';
            const numericValue = 1080;
            var multiline = "first line"
            var next = 99;
        """.trimIndent()

        assertEquals("https://cdn.tv/stream.m3u8", ZeroAllocParser.extractJsAssignment(js, "standardUrl"))
        assertEquals("my_secret_token_value", ZeroAllocParser.extractJsAssignment(js, "singleQuote"))
        assertEquals("1080", ZeroAllocParser.extractJsAssignment(js, "numericValue"))
        assertEquals("first line", ZeroAllocParser.extractJsAssignment(js, "multiline"))
        assertNull(ZeroAllocParser.extractJsAssignment(js, "nonExistent"))
    }

    // =========================================================================
    // PART 9: Malformed HTML Resilience & Performance Fuzzing
    // =========================================================================

    @Test
    fun testMalformedHtmlResilience() {
        // Unclosed tags, unquoted attributes, missing closing brackets
        val malformedHtml = """
            <div class="file_quality" data-url=https://febbox.com/unquoted.mp4 data-quality="1080p">
                <span class="size">2.1 GB
            <div class="file_quality" data-url="https://febbox.com/no_size.mp4" data-quality="720p">
            <div>Some random text without closing tags
        """.trimIndent()

        val results = ZeroAllocParser.extractFileQualities(malformedHtml)
        assertEquals(1, results.size)
        assertEquals("https://febbox.com/unquoted.mp4", results[0].first)
        assertEquals("1080p", results[0].second)
        assertEquals("2.1 GB", results[0].third)

        // Edge case inputs
        assertTrue(ZeroAllocParser.extractFileQualities("").isEmpty())
        assertTrue(ZeroAllocParser.extractFileQualities("   \n\t  ").isEmpty())
        assertNull(ZeroAllocParser.extractIframeSrc(""))
        assertNull(ZeroAllocParser.extractScriptContaining("", "foo"))
        assertNull(ZeroAllocParser.extractJsonBlock("", "foo"))
    }

    @Test
    fun testHighVolumeHtmlPayloadBenchmark() {
        // 500 fake divs + 50 target file_quality divs (~100KB payload)
        val payload = buildString {
            append("<!DOCTYPE html><html><body><div id='container'>")
            for (i in 1..500) {
                append("<div class='item item-$i' data-id='$i'><p>Paragraph $i</p><span>Span $i</span></div>\n")
            }
            for (i in 1..50) {
                append("<div class='file_quality' data-url='https://cdn.stream.tv/file_$i.mp4' data-quality='${i * 10}p'><span class='size'>$i MB</span></div>\n")
            }
            append("</div></body></html>")
        }

        // Warmup
        for (i in 1..10) {
            ZeroAllocParser.extractFileQualities(payload)
        }

        val start = System.nanoTime()
        val qualities = ZeroAllocParser.extractFileQualities(payload)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(50, qualities.size)
        assertTrue("Zero-allocation parse on 100KB HTML must execute in under 30ms (took ${elapsedMs}ms)", elapsedMs < 30L)
    }
}
