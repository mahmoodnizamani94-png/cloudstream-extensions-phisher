package com.phisher98

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class StremioAddonOptimizationTest {

    private var originalClient: OkHttpClient? = null

    @Before
    fun setUp() {
        TrackerManager.clearCache()
        originalClient = app.baseClient
    }

    @After
    fun tearDown() {
        TrackerManager.clearCache()
        originalClient?.let { app.baseClient = it }
    }

    @Test
    fun testTrackerFormattingFromRawText() {
        val rawInput = """
            udp://tracker.example.org:1337/announce

            udp://tracker2.example.org:6969/announce

            http://tracker3.example.org:80/announce
        """.trimIndent()

        val formatted = TrackerManager.parseAndFormatTrackers(rawInput)

        assertTrue(formatted.contains("&tr=udp://tracker.example.org:1337/announce"))
        assertTrue(formatted.contains("&tr=udp://tracker2.example.org:6969/announce"))
        assertTrue(formatted.contains("&tr=http://tracker3.example.org:80/announce"))
        assertTrue(formatted.startsWith("&tr="))
    }

    @Test
    fun testFallbackTrackersFormatted() {
        val fallback = TrackerManager.getFallbackTrackersFormatted()

        assertNotNull(fallback)
        assertTrue(fallback.isNotBlank())
        assertTrue(fallback.startsWith("&tr="))
        assertTrue(fallback.contains("tracker.opentrackr.org"))
        assertTrue(fallback.contains("openbittorrent.com"))
    }

    @Test
    fun testTrackerManagerCacheHit() = runBlocking {
        val testTrackers = "&tr=udp://cached.tracker.org:1337/announce"
        TrackerManager.setCacheForTesting(testTrackers, System.currentTimeMillis())

        val result = TrackerManager.getFormattedTrackers()
        assertEquals(testTrackers, result)
    }

    @Test
    fun testTrackerManagerMutexLockingUnder50ConcurrentCalls() = runBlocking {
        TrackerManager.clearCache()

        val networkCallCount = AtomicInteger(0)
        val mockTrackersResponse = """
            udp://mock.opentrackr.org:1337/announce

            udp://mock.openbittorrent.com:6969/announce

            udp://mock.torrent.eu.org:451/announce
        """.trimIndent()

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.url.toString() == StremioAddon.TRACKER_LIST_URL) {
                networkCallCount.incrementAndGet()
                // Inject artificial latency so all 50 coroutines are in-flight simultaneously
                Thread.sleep(60L)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockTrackersResponse.toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            } else {
                chain.proceed(request)
            }
        }

        app.baseClient = originalClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val concurrency = 50
        val deferreds = (1..concurrency).map {
            async(Dispatchers.IO) {
                TrackerManager.getFormattedTrackers()
            }
        }

        val results = deferreds.awaitAll()

        // 1. All 50 concurrent calls must succeed
        assertEquals(concurrency, results.size)

        // 2. Exactly 1 network call must be made across all 50 simultaneous callers
        assertEquals("Expected exactly 1 network call due to double-checked Mutex locking", 1, networkCallCount.get())

        // 3. All callers must receive the parsed trackers string
        val expectedFormat = TrackerManager.parseAndFormatTrackers(mockTrackersResponse)
        results.forEach { result ->
            assertEquals(expectedFormat, result)
            assertTrue(result.contains("mock.opentrackr.org"))
        }

        // 4. A follow-up call within TTL should also make 0 additional network calls
        val followup = TrackerManager.getFormattedTrackers()
        assertEquals(expectedFormat, followup)
        assertEquals(1, networkCallCount.get())
    }

    @Test
    fun testTrackerManagerFallbackOnSimulatedNetworkFailure() = runBlocking {
        TrackerManager.clearCache()

        val networkAttempts = AtomicInteger(0)

        val failingInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.url.toString() == StremioAddon.TRACKER_LIST_URL) {
                networkAttempts.incrementAndGet()
                throw IOException("Simulated network connection timeout / HTTP 500 error")
            }
            chain.proceed(request)
        }

        app.baseClient = originalClient!!.newBuilder()
            .addInterceptor(failingInterceptor)
            .build()

        val result = TrackerManager.getFormattedTrackers()

        // Network call was attempted
        assertEquals(1, networkAttempts.get())

        // Result MUST be fallback trackers
        val expectedFallback = TrackerManager.getFallbackTrackersFormatted()
        assertEquals(expectedFallback, result)
        assertTrue(result.startsWith("&tr="))
        assertTrue(result.contains("tracker.opentrackr.org"))
        assertTrue(result.contains("openbittorrent.com"))
    }

    @Test
    fun testTrackerManagerFallbackOnEmptyResponseBody() = runBlocking {
        TrackerManager.clearCache()

        val emptyInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.url.toString() == StremioAddon.TRACKER_LIST_URL) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            } else {
                chain.proceed(request)
            }
        }

        app.baseClient = originalClient!!.newBuilder()
            .addInterceptor(emptyInterceptor)
            .build()

        val result = TrackerManager.getFormattedTrackers()
        val expectedFallback = TrackerManager.getFallbackTrackersFormatted()
        assertEquals("Empty response from remote should trigger fallback trackers", expectedFallback, result)
    }

    @Test
    fun testTrackerManagerConcurrentFailuresFallbackOnce() = runBlocking {
        TrackerManager.clearCache()

        val networkAttempts = AtomicInteger(0)

        val failingInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.url.toString() == StremioAddon.TRACKER_LIST_URL) {
                networkAttempts.incrementAndGet()
                Thread.sleep(50L) // Latency during network failure
                throw IOException("Simulated DNS resolution failure")
            }
            chain.proceed(request)
        }

        app.baseClient = originalClient!!.newBuilder()
            .addInterceptor(failingInterceptor)
            .build()

        val concurrency = 50
        val deferreds = (1..concurrency).map {
            async(Dispatchers.IO) {
                TrackerManager.getFormattedTrackers()
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(concurrency, results.size)
        // Only 1 network attempt should be made even when it fails, and all callers get fallback trackers
        assertEquals(1, networkAttempts.get())
        val expectedFallback = TrackerManager.getFallbackTrackersFormatted()
        results.forEach { assertEquals(expectedFallback, it) }
    }

    @Test
    fun testTrackerManagerCacheTtlExpirationWithNetworkCall() = runBlocking {
        TrackerManager.clearCache()

        val networkCallCount = AtomicInteger(0)
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.url.toString() == StremioAddon.TRACKER_LIST_URL) {
                val call = networkCallCount.incrementAndGet()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("udp://gen$call.tracker.org:1337/announce".toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            } else {
                chain.proceed(request)
            }
        }

        app.baseClient = originalClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        // 1. Initial call
        val firstResult = TrackerManager.getFormattedTrackers()
        assertEquals(1, networkCallCount.get())
        assertTrue(firstResult.contains("gen1.tracker.org"))

        // 2. Immediate second call should be cached (0 network calls)
        val secondResult = TrackerManager.getFormattedTrackers()
        assertEquals(1, networkCallCount.get())
        assertEquals(firstResult, secondResult)

        // 3. Expire cache timestamp artificially past 12h TTL
        TrackerManager.setCacheForTesting(secondResult, System.currentTimeMillis() - (TrackerManager.TRACKER_TTL_MS + 1000L))

        // 4. Third call should refresh from network
        val thirdResult = TrackerManager.getFormattedTrackers()
        assertEquals(2, networkCallCount.get())
        assertTrue(thirdResult.contains("gen2.tracker.org"))
    }

    @Test
    fun testGetQualityDetection() {
        // Standard resolutions
        val quality1080 = extractQualityString(listOf("Movie.Title.1080p.BluRay.x264"))
        assertEquals("1080p", quality1080)

        val quality720 = extractQualityString(listOf("Show.S01E01.720p.HDTV"))
        assertEquals("720p", quality720)

        val quality4k = extractQualityString(listOf("Nature.Documentary.4k.UHD"))
        assertEquals("2160p", quality4k)

        val quality2160 = extractQualityString(listOf("Feature.Film.2160p.HDR"))
        assertEquals("2160p", quality2160)

        // Multiple candidates: should pick the first matching quality
        val qualityMulti = extractQualityString(listOf("Release", "Some.1080P.Release", "Other"))
        assertEquals("1080P", qualityMulti)

        // Unknown quality
        val qualityUnknown = extractQualityString(listOf("Unknown Title"))
        assertNull(qualityUnknown)
    }
}
